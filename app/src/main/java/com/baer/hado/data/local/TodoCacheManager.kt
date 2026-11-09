package com.baer.hado.data.local

import android.content.Context
import com.baer.hado.data.model.HaState
import com.baer.hado.data.model.PendingOperationType
import com.baer.hado.data.model.PendingTodoOperation
import com.baer.hado.data.model.TodoItem
import com.baer.hado.data.model.TodoItemStatus
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object TodoCacheManager {
    // Reuses the existing hado_items_cache prefs so previously cached items remain intact on upgrade
    private const val ITEMS_PREFS_NAME = "hado_items_cache"
    private const val LISTS_PREFS_NAME = "hado_lists_cache"
    private const val KEY_LISTS_PREFIX = "lists_"

    private val gson = Gson()
    private val itemsType = object : TypeToken<List<TodoItem>>() {}.type
    private val listsType = object : TypeToken<List<HaState>>() {}.type

    data class CacheUpdateEvent(
        val accountId: String?,
        val entityId: String? = null
    )

    private val _updates = MutableSharedFlow<CacheUpdateEvent>(extraBufferCapacity = 64)
    val updates: SharedFlow<CacheUpdateEvent> = _updates.asSharedFlow()

    private fun itemsPrefs(context: Context) =
        context.getSharedPreferences(ITEMS_PREFS_NAME, Context.MODE_PRIVATE)

    private fun listsPrefs(context: Context) =
        context.getSharedPreferences(LISTS_PREFS_NAME, Context.MODE_PRIVATE)

    private fun listsKey(accountId: String?): String =
        KEY_LISTS_PREFIX + (accountId ?: TokenManager.PRIMARY_ACCOUNT_ID)

    // --- Lists Cache ---

    fun loadLists(context: Context, accountId: String?): List<HaState>? {
        val json = listsPrefs(context).getString(listsKey(accountId), null) ?: return null
        return try {
            gson.fromJson<List<HaState>>(json, listsType)
        } catch (_: Exception) {
            null
        }
    }

    fun saveLists(context: Context, accountId: String?, lists: List<HaState>, notify: Boolean = true) {
        listsPrefs(context).edit()
            .putString(listsKey(accountId), gson.toJson(lists))
            .apply()
        if (notify) {
            _updates.tryEmit(CacheUpdateEvent(accountId = accountId, entityId = null))
        }
    }

    // --- Items Cache ---

    fun loadItems(context: Context, accountId: String?, entityId: String): List<TodoItem>? {
        val key = TokenManager.scopedKey(accountId, entityId)
        val json = itemsPrefs(context).getString(key, null) ?: return null
        return try {
            gson.fromJson<List<TodoItem>>(json, itemsType)
        } catch (_: Exception) {
            null
        }
    }

    fun saveItems(
        context: Context,
        accountId: String?,
        entityId: String,
        items: List<TodoItem>,
        notify: Boolean = true
    ) {
        val key = TokenManager.scopedKey(accountId, entityId)
        itemsPrefs(context).edit()
            .putString(key, gson.toJson(items))
            .apply()
        if (notify) {
            _updates.tryEmit(CacheUpdateEvent(accountId = accountId, entityId = entityId))
        }
    }

    /**
     * Replaces [oldUid] with [newItem] in the cached items for [entityId].
     */
    fun replaceItemUid(
        context: Context,
        accountId: String?,
        entityId: String,
        oldUid: String,
        newItem: TodoItem,
        notify: Boolean = true
    ) {
        val current = loadItems(context, accountId, entityId) ?: return
        if (current.none { it.uid == oldUid }) return
        val updated = current.map { if (it.uid == oldUid) newItem else it }
        saveItems(context, accountId, entityId, updated, notify = notify)
    }

    /**
     * Applies any pending operations in [PendingSyncStore] for [entityId] on top of [remoteItems],
     * saves the resulting effective list to the local cache, and returns it.
     */
    fun mergeRemoteItemsAndSave(
        context: Context,
        accountId: String?,
        entityId: String,
        remoteItems: List<TodoItem>,
        notify: Boolean = true
    ): List<TodoItem> {
        val queue = PendingSyncStore.loadQueue(context, accountId)
        val effective = applyPendingOperations(entityId, remoteItems, queue)
        saveItems(context, accountId, entityId, effective, notify = notify)
        return effective
    }

    /**
     * Returns the cached items for [entityId] with any pending sync operations overlaid.
     */
    fun getEffectiveItems(context: Context, accountId: String?, entityId: String): List<TodoItem>? {
        val cached = loadItems(context, accountId, entityId) ?: return null
        val queue = PendingSyncStore.loadQueue(context, accountId)
        if (queue.isEmpty()) return cached
        return applyPendingOperations(entityId, cached, queue)
    }

    fun applyPendingOperations(
        entityId: String,
        baseItems: List<TodoItem>,
        queue: List<PendingTodoOperation>
    ): List<TodoItem> {
        if (queue.isEmpty()) return baseItems
        val items = baseItems.toMutableList()

        for (op in queue) {
            when (op.type) {
                PendingOperationType.ADD -> {
                    if (op.entityId != entityId) continue
                    val summary = op.summary ?: continue
                    val newItem = TodoItem(
                        uid = op.itemUid,
                        summary = summary,
                        status = op.status ?: TodoItemStatus.NEEDS_ACTION,
                        description = op.description,
                        due = op.due
                    )
                    val existingIdx = items.indexOfFirst { it.uid == op.itemUid }
                    if (existingIdx >= 0) {
                        items[existingIdx] = newItem
                    } else {
                        when (op.position) {
                            AddItemPosition.TOP -> items.add(0, newItem)
                            AddItemPosition.BOTTOM -> items.add(newItem)
                        }
                    }
                }

                PendingOperationType.UPDATE -> {
                    if (op.entityId != entityId) continue
                    val idx = items.indexOfFirst { it.uid == op.itemUid }
                    if (idx >= 0) {
                        var item = items[idx]
                        op.summary?.let { item = item.copy(summary = it) }
                        op.status?.let { item = item.copy(status = it) }
                        if (op.hasDescriptionUpdate) {
                            item = item.copy(description = op.description)
                        }
                        if (op.hasDueUpdate) {
                            item = item.copy(due = op.due)
                        }
                        items[idx] = item
                    }
                }

                PendingOperationType.DELETE -> {
                    if (op.entityId != entityId) continue
                    items.removeAll { it.uid == op.itemUid }
                }

                PendingOperationType.REORDER -> {
                    if (op.entityId != entityId) continue
                    val item = items.firstOrNull { it.uid == op.itemUid } ?: continue
                    items.removeAll { it.uid == op.itemUid }
                    if (op.previousUid == null) {
                        items.add(0, item)
                    } else {
                        val prevIdx = items.indexOfFirst { it.uid == op.previousUid }
                        if (prevIdx >= 0) {
                            items.add(prevIdx + 1, item)
                        } else {
                            items.add(item)
                        }
                    }
                }

                PendingOperationType.MOVE_TO_LIST -> {
                    if (op.entityId == entityId) {
                        items.removeAll { it.uid == op.itemUid }
                    } else if (op.targetEntityId == entityId) {
                        val summary = op.summary ?: continue
                        val movedItem = TodoItem(
                            uid = op.itemUid,
                            summary = summary,
                            status = op.status ?: TodoItemStatus.NEEDS_ACTION,
                            description = op.description,
                            due = op.due
                        )
                        val existingIdx = items.indexOfFirst { it.uid == op.itemUid }
                        if (existingIdx >= 0) {
                            items[existingIdx] = movedItem
                        } else {
                            when (op.position) {
                                AddItemPosition.TOP -> items.add(0, movedItem)
                                AddItemPosition.BOTTOM -> items.add(movedItem)
                            }
                        }
                    }
                }
            }
        }
        return items
    }

    fun notifyChanged(accountId: String?, entityId: String? = null) {
        _updates.tryEmit(CacheUpdateEvent(accountId = accountId, entityId = entityId))
    }

    fun clearAccount(context: Context, accountId: String?) {
        listsPrefs(context).edit().remove(listsKey(accountId)).apply()
        val prefix = if (accountId == null || accountId == TokenManager.PRIMARY_ACCOUNT_ID) {
            null
        } else {
            "${accountId}__"
        }
        val itemsEditor = itemsPrefs(context).edit()
        for (key in itemsPrefs(context).all.keys) {
            if (prefix == null) {
                if (!key.contains("__")) itemsEditor.remove(key)
            } else if (key.startsWith(prefix)) {
                itemsEditor.remove(key)
            }
        }
        itemsEditor.apply()
    }

    fun clearAll(context: Context) {
        listsPrefs(context).edit().clear().apply()
        itemsPrefs(context).edit().clear().apply()
    }
}
