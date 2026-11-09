package com.baer.hado.data.sync

import android.content.Context
import android.util.Log
import com.baer.hado.data.local.AddItemPosition
import com.baer.hado.data.local.LocalTodoStore
import com.baer.hado.data.local.PendingSyncStore
import com.baer.hado.data.local.TodoCacheManager
import com.baer.hado.data.local.TokenManager
import com.baer.hado.data.model.PendingOperationType
import com.baer.hado.data.model.PendingTodoOperation
import com.baer.hado.data.model.TodoItem
import com.baer.hado.data.model.TodoItemStatus
import com.baer.hado.data.model.TodoListFeature
import com.baer.hado.widget.AddedItem
import com.baer.hado.widget.TodoWidgetWorker
import com.baer.hado.widget.WidgetHttpClient
import com.baer.hado.widget.WidgetStateMutator
import com.baer.hado.widget.buildMovePayload
import com.baer.hado.widget.fetchListItems
import com.baer.hado.widget.resolveAddedItem
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object TodoSyncManager {
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val accountMutexes = ConcurrentHashMap<String, Mutex>()
    private val gson = Gson()

    private fun mutexFor(accountId: String?): Mutex {
        val key = accountId ?: TokenManager.PRIMARY_ACCOUNT_ID
        return accountMutexes.computeIfAbsent(key) { Mutex() }
    }

    /**
     * Instantly creates a task in the local cache (and LocalTodoStore if in Demo Mode),
     * updates matching home screen widgets, and schedules remote sync with Home Assistant.
     */
    fun addItem(
        context: Context,
        accountId: String?,
        entityId: String,
        summary: String,
        description: String? = null,
        due: String? = null,
        position: AddItemPosition = AddItemPosition.TOP,
        isLocalMode: Boolean = TokenManager(context).isDemoMode,
        preferredAppWidgetId: Int? = null
    ): TodoItem {
        val appContext = context.applicationContext
        val trimmed = summary.trim()
        if (isLocalMode) {
            val item = LocalTodoStore(appContext).addItem(
                entityId = entityId,
                summary = trimmed,
                description = description,
                due = due,
                position = position
            )
            val allItems = LocalTodoStore(appContext).getItems(entityId)
            TodoCacheManager.saveItems(appContext, null, entityId, allItems, notify = true)
            syncScope.launch {
                WidgetStateMutator.addItem(appContext, null, entityId, item, position, preferredAppWidgetId)
                enqueueWidgetRefresh(appContext, preferredAppWidgetId)
            }
            return item
        }

        val resolvedAccountId = resolveAccountId(appContext, accountId)
        val item = TodoItem(
            uid = "${PendingSyncStore.LOCAL_UID_PREFIX}${UUID.randomUUID()}",
            summary = trimmed,
            status = TodoItemStatus.NEEDS_ACTION,
            description = description?.takeIf { it.isNotBlank() },
            due = due?.takeIf { it.isNotBlank() }
        )

        val current = TodoCacheManager.loadItems(appContext, resolvedAccountId, entityId).orEmpty()
        val updated = when (position) {
            AddItemPosition.TOP -> listOf(item) + current
            AddItemPosition.BOTTOM -> current + listOf(item)
        }
        PendingSyncStore.enqueueAdd(appContext, resolvedAccountId, entityId, item, position)
        TodoCacheManager.saveItems(appContext, resolvedAccountId, entityId, updated, notify = true)

        syncScope.launch {
            WidgetStateMutator.addItem(appContext, resolvedAccountId, entityId, item, position, preferredAppWidgetId)
        }
        triggerSync(appContext, resolvedAccountId, preferredAppWidgetId)
        return item
    }

    /**
     * Instantly toggles a task's status locally and schedules remote sync.
     */
    fun toggleItem(
        context: Context,
        accountId: String?,
        entityId: String,
        itemUid: String,
        completed: Boolean,
        isLocalMode: Boolean = TokenManager(context).isDemoMode,
        preferredAppWidgetId: Int? = null
    ) {
        val appContext = context.applicationContext
        val newStatus = if (completed) TodoItemStatus.COMPLETED else TodoItemStatus.NEEDS_ACTION
        if (isLocalMode) {
            LocalTodoStore(appContext).updateItemStatus(entityId, itemUid, completed)
            val allItems = LocalTodoStore(appContext).getItems(entityId)
            TodoCacheManager.saveItems(appContext, null, entityId, allItems, notify = true)
            syncScope.launch {
                val updatedItem = allItems.firstOrNull { it.uid == itemUid }
                if (updatedItem != null) {
                    WidgetStateMutator.updateItem(appContext, null, entityId, updatedItem, preferredAppWidgetId)
                }
                enqueueWidgetRefresh(appContext, preferredAppWidgetId)
            }
            return
        }

        val resolvedAccountId = resolveAccountId(appContext, accountId)
        val current = TodoCacheManager.loadItems(appContext, resolvedAccountId, entityId).orEmpty()
        val updated = current.map {
            if (it.uid == itemUid) it.copy(status = newStatus) else it
        }
        PendingSyncStore.enqueueToggleStatus(appContext, resolvedAccountId, entityId, itemUid, newStatus)
        TodoCacheManager.saveItems(appContext, resolvedAccountId, entityId, updated, notify = true)

        val updatedItem = updated.firstOrNull { it.uid == itemUid }
        if (updatedItem != null) {
            syncScope.launch {
                WidgetStateMutator.updateItem(appContext, resolvedAccountId, entityId, updatedItem, preferredAppWidgetId)
            }
        }
        triggerSync(appContext, resolvedAccountId, preferredAppWidgetId)
    }

    /**
     * Instantly updates a task's details locally and schedules remote sync.
     */
    fun updateItemDetails(
        context: Context,
        accountId: String?,
        entityId: String,
        original: TodoItem,
        updated: TodoItem,
        isLocalMode: Boolean = TokenManager(context).isDemoMode,
        preferredAppWidgetId: Int? = null
    ) {
        val appContext = context.applicationContext
        if (isLocalMode) {
            LocalTodoStore(appContext).updateItemDetails(
                entityId = entityId,
                itemUid = original.uid,
                rename = updated.summary,
                status = if (updated.isCompleted) "completed" else "needs_action",
                description = updated.description,
                due = updated.due
            )
            val allItems = LocalTodoStore(appContext).getItems(entityId)
            TodoCacheManager.saveItems(appContext, null, entityId, allItems, notify = true)
            syncScope.launch {
                WidgetStateMutator.updateItem(appContext, null, entityId, updated, preferredAppWidgetId)
                enqueueWidgetRefresh(appContext, preferredAppWidgetId)
            }
            return
        }

        val resolvedAccountId = resolveAccountId(appContext, accountId)
        val current = TodoCacheManager.loadItems(appContext, resolvedAccountId, entityId).orEmpty()
        val updatedList = current.map { if (it.uid == original.uid) updated else it }
        PendingSyncStore.enqueueUpdateDetails(appContext, resolvedAccountId, entityId, original, updated)
        TodoCacheManager.saveItems(appContext, resolvedAccountId, entityId, updatedList, notify = true)

        syncScope.launch {
            WidgetStateMutator.updateItem(appContext, resolvedAccountId, entityId, updated, preferredAppWidgetId)
        }
        triggerSync(appContext, resolvedAccountId, preferredAppWidgetId)
    }

    /**
     * Instantly deletes a task locally and schedules remote sync.
     */
    fun deleteItem(
        context: Context,
        accountId: String?,
        entityId: String,
        itemUid: String,
        isLocalMode: Boolean = TokenManager(context).isDemoMode,
        preferredAppWidgetId: Int? = null
    ) {
        val appContext = context.applicationContext
        if (isLocalMode) {
            LocalTodoStore(appContext).removeItem(entityId, itemUid)
            val allItems = LocalTodoStore(appContext).getItems(entityId)
            TodoCacheManager.saveItems(appContext, null, entityId, allItems, notify = true)
            syncScope.launch {
                WidgetStateMutator.removeItem(appContext, null, entityId, itemUid, preferredAppWidgetId)
                enqueueWidgetRefresh(appContext, preferredAppWidgetId)
            }
            return
        }

        val resolvedAccountId = resolveAccountId(appContext, accountId)
        val current = TodoCacheManager.loadItems(appContext, resolvedAccountId, entityId).orEmpty()
        val updated = current.filterNot { it.uid == itemUid }
        PendingSyncStore.enqueueDelete(appContext, resolvedAccountId, entityId, itemUid)
        TodoCacheManager.saveItems(appContext, resolvedAccountId, entityId, updated, notify = true)

        syncScope.launch {
            WidgetStateMutator.removeItem(appContext, resolvedAccountId, entityId, itemUid, preferredAppWidgetId)
        }
        triggerSync(appContext, resolvedAccountId, preferredAppWidgetId)
    }

    /**
     * Instantly saves the reordered item list locally and schedules remote sync.
     */
    fun reorderItem(
        context: Context,
        accountId: String?,
        entityId: String,
        reorderedItems: List<TodoItem>,
        movedUid: String,
        previousUid: String?,
        isLocalMode: Boolean = TokenManager(context).isDemoMode,
        preferredAppWidgetId: Int? = null
    ) {
        val appContext = context.applicationContext
        if (isLocalMode) {
            LocalTodoStore(appContext).moveItem(entityId, movedUid, previousUid)
            val allItems = LocalTodoStore(appContext).getItems(entityId)
            TodoCacheManager.saveItems(appContext, null, entityId, allItems, notify = true)
            syncScope.launch {
                enqueueWidgetRefresh(appContext, preferredAppWidgetId)
            }
            return
        }

        val resolvedAccountId = resolveAccountId(appContext, accountId)
        PendingSyncStore.enqueueReorder(appContext, resolvedAccountId, entityId, movedUid, previousUid)
        TodoCacheManager.saveItems(appContext, resolvedAccountId, entityId, reorderedItems, notify = true)
        triggerSync(appContext, resolvedAccountId, preferredAppWidgetId)
    }

    /**
     * Instantly moves a task from [fromEntityId] to [toEntityId] locally and schedules remote sync.
     */
    fun moveItemToList(
        context: Context,
        accountId: String?,
        fromEntityId: String,
        toEntityId: String,
        targetFeatures: Int,
        item: TodoItem,
        position: AddItemPosition,
        isLocalMode: Boolean = TokenManager(context).isDemoMode,
        preferredAppWidgetId: Int? = null
    ): TodoItem {
        val appContext = context.applicationContext
        val plan = buildMovePayload(item, targetFeatures)
        val movedItem = item.copy(
            description = plan.description,
            due = plan.due,
            status = if (plan.markCompleted) TodoItemStatus.COMPLETED else TodoItemStatus.NEEDS_ACTION
        )

        if (isLocalMode) {
            val localStore = LocalTodoStore(appContext)
            localStore.moveItemToList(fromEntityId, toEntityId, movedItem, position)
            TodoCacheManager.saveItems(appContext, null, fromEntityId, localStore.getItems(fromEntityId), notify = true)
            TodoCacheManager.saveItems(appContext, null, toEntityId, localStore.getItems(toEntityId), notify = true)
            syncScope.launch {
                WidgetStateMutator.removeItem(appContext, null, fromEntityId, item.uid, preferredAppWidgetId)
                WidgetStateMutator.addItem(appContext, null, toEntityId, movedItem, position, preferredAppWidgetId)
                enqueueWidgetRefresh(appContext, preferredAppWidgetId)
            }
            return movedItem
        }

        val resolvedAccountId = resolveAccountId(appContext, accountId)
        val sourceItems = TodoCacheManager.loadItems(appContext, resolvedAccountId, fromEntityId).orEmpty()
            .filterNot { it.uid == item.uid }
        val targetItems = TodoCacheManager.loadItems(appContext, resolvedAccountId, toEntityId).orEmpty()
            .filterNot { it.uid == item.uid }
        val updatedTargetItems = when (position) {
            AddItemPosition.TOP -> listOf(movedItem) + targetItems
            AddItemPosition.BOTTOM -> targetItems + listOf(movedItem)
        }

        PendingSyncStore.enqueueMoveToList(
            context = appContext,
            accountId = resolvedAccountId,
            fromEntityId = fromEntityId,
            toEntityId = toEntityId,
            targetFeatures = targetFeatures,
            movedItem = movedItem,
            position = position
        )
        TodoCacheManager.saveItems(appContext, resolvedAccountId, fromEntityId, sourceItems, notify = true)
        TodoCacheManager.saveItems(appContext, resolvedAccountId, toEntityId, updatedTargetItems, notify = true)

        syncScope.launch {
            WidgetStateMutator.removeItem(appContext, resolvedAccountId, fromEntityId, item.uid, preferredAppWidgetId)
            WidgetStateMutator.addItem(appContext, resolvedAccountId, toEntityId, movedItem, position, preferredAppWidgetId)
        }
        triggerSync(appContext, resolvedAccountId, preferredAppWidgetId)
        return movedItem
    }

    /**
     * Triggers an immediate background sync in [syncScope] and schedules [TodoSyncWorker]
     * with a network constraint so offline operations are guaranteed to sync when connectivity returns.
     */
    fun triggerSync(context: Context, accountId: String? = null, preferredAppWidgetId: Int? = null) {
        val appContext = context.applicationContext
        val resolvedAccountId = resolveAccountId(appContext, accountId)
        TodoSyncWorker.enqueue(appContext, resolvedAccountId)
        syncScope.launch {
            val syncedAny = drainQueue(appContext, resolvedAccountId)
            if (syncedAny) {
                enqueueWidgetRefresh(appContext, preferredAppWidgetId)
            }
        }
    }

    /**
     * Drains all pending operations for [accountId] in FIFO order.
     * Returns true if the queue is now completely empty, or false if a transient error stopped draining.
     */
    suspend fun drainQueue(context: Context, accountId: String?): Boolean {
        val appContext = context.applicationContext
        val tokenManager = TokenManager(appContext)
        if (tokenManager.isDemoMode) return true

        val resolvedAccountId = resolveAccountId(appContext, accountId)
        val mutex = mutexFor(resolvedAccountId)
        return mutex.withLock {
            val httpClient = WidgetHttpClient(appContext, resolvedAccountId)
            if (!httpClient.hasRemoteSession()) return@withLock true

            while (true) {
                val queue = PendingSyncStore.loadQueue(appContext, resolvedAccountId)
                val op = queue.firstOrNull() ?: return@withLock true

                val outcome = executeOperation(appContext, resolvedAccountId, httpClient, op)
                when (outcome) {
                    OpOutcome.SUCCESS -> {
                        PendingSyncStore.removeOperation(appContext, resolvedAccountId, op.id)
                    }
                    OpOutcome.PERMANENT_ERROR -> {
                        Log.w("HAdo", "Dropping permanently failed sync op: ${op.type} on ${op.entityId}")
                        PendingSyncStore.removeOperation(appContext, resolvedAccountId, op.id)
                        reconcileEntityFromServer(appContext, resolvedAccountId, httpClient, op.entityId)
                        op.targetEntityId?.let { targetId ->
                            reconcileEntityFromServer(appContext, resolvedAccountId, httpClient, targetId)
                        }
                    }
                    OpOutcome.TRANSIENT_ERROR -> {
                        Log.d("HAdo", "Transient sync failure for ${op.type} on ${op.entityId}, will retry")
                        return@withLock false
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            true
        }
    }

    private enum class OpOutcome {
        SUCCESS,
        TRANSIENT_ERROR,
        PERMANENT_ERROR
    }

    private suspend fun executeOperation(
        context: Context,
        accountId: String?,
        httpClient: WidgetHttpClient,
        op: PendingTodoOperation
    ): OpOutcome {
        return try {
            when (op.type) {
                PendingOperationType.ADD -> executeAdd(context, accountId, httpClient, op)
                PendingOperationType.UPDATE -> executeUpdate(httpClient, op)
                PendingOperationType.DELETE -> executeDelete(httpClient, op)
                PendingOperationType.REORDER -> executeReorder(httpClient, op)
                PendingOperationType.MOVE_TO_LIST -> executeMoveToList(context, accountId, httpClient, op)
            }
        } catch (e: Exception) {
            Log.e("HAdo", "Exception executing sync op ${op.type}", e)
            OpOutcome.TRANSIENT_ERROR
        }
    }

    private suspend fun executeAdd(
        context: Context,
        accountId: String?,
        httpClient: WidgetHttpClient,
        op: PendingTodoOperation
    ): OpOutcome {
        val summary = op.summary?.takeIf { it.isNotBlank() } ?: return OpOutcome.PERMANENT_ERROR
        val knownUids = fetchListItems(httpClient, gson, op.entityId)?.map { it.uid }?.toSet() ?: emptySet()

        val body = mutableMapOf(
            "entity_id" to op.entityId,
            "item" to summary
        )
        if (!op.description.isNullOrBlank()) {
            body["description"] = op.description
        }
        val due = op.due?.takeIf { it.isNotBlank() }
        if (due != null) {
            if (due.contains("T") || (due.contains(" ") && due.length > 10)) {
                body["due_datetime"] = due.replace("T", " ")
            } else {
                body["due_date"] = due
            }
        }

        val response = httpClient.post("api/services/todo/add_item", gson.toJson(body))
            ?: return OpOutcome.TRANSIENT_ERROR

        val code = response.use { resp ->
            if (!resp.isSuccessful) {
                Log.w("HAdo", "Sync ADD failed: ${resp.code} ${resp.body?.string()}")
            }
            resp.code
        }

        if (code !in 200..299) {
            return classifyHttpFailure(code)
        }

        val added: AddedItem? = resolveAddedItem(
            httpClient = httpClient,
            gson = gson,
            entityId = op.entityId,
            knownItemUids = knownUids,
            expectedSummary = summary
        )

        if (added != null) {
            val remoteItem = added.item
            if (op.position == AddItemPosition.TOP && !added.alreadyFirstActive && op.status != TodoItemStatus.COMPLETED) {
                httpClient.moveTodoItem(op.entityId, remoteItem.uid, null)
            }
            val finalItem = if (op.status == TodoItemStatus.COMPLETED) {
                val statusBody = mapOf(
                    "entity_id" to op.entityId,
                    "item" to remoteItem.uid,
                    "status" to "completed"
                )
                httpClient.post("api/services/todo/update_item", gson.toJson(statusBody))?.close()
                remoteItem.copy(status = TodoItemStatus.COMPLETED)
            } else {
                remoteItem
            }

            // Replace local UID with remote UID in queue, cache, and widgets
            PendingSyncStore.replaceUid(context, accountId, op.entityId, op.itemUid, finalItem.uid)
            TodoCacheManager.replaceItemUid(context, accountId, op.entityId, op.itemUid, finalItem, notify = true)
            WidgetStateMutator.replaceItem(context, accountId, op.entityId, op.itemUid, finalItem)
        }

        return OpOutcome.SUCCESS
    }

    private fun executeUpdate(
        httpClient: WidgetHttpClient,
        op: PendingTodoOperation
    ): OpOutcome {
        if (op.itemUid.startsWith(PendingSyncStore.LOCAL_UID_PREFIX)) {
            // Unresolved local UID with no matching ADD in front of it
            return OpOutcome.PERMANENT_ERROR
        }

        val body = mutableMapOf(
            "entity_id" to op.entityId,
            "item" to op.itemUid
        )
        op.summary?.let { body["rename"] = it }
        op.status?.let { status ->
            body["status"] = if (status == TodoItemStatus.COMPLETED) "completed" else "needs_action"
        }
        if (op.hasDescriptionUpdate) {
            body["description"] = op.description ?: ""
        }
        if (op.hasDueUpdate) {
            val due = op.due
            if (due.isNullOrBlank()) {
                body["due_date"] = ""
            } else if (due.contains("T") || (due.contains(" ") && due.length > 10)) {
                body["due_datetime"] = due.replace("T", " ")
            } else {
                body["due_date"] = due
            }
        }

        if (body.size <= 2) return OpOutcome.SUCCESS

        val response = httpClient.post("api/services/todo/update_item", gson.toJson(body))
            ?: return OpOutcome.TRANSIENT_ERROR

        val code = response.use { resp ->
            if (!resp.isSuccessful) {
                Log.w("HAdo", "Sync UPDATE failed: ${resp.code} ${resp.body?.string()}")
            }
            resp.code
        }

        return if (code in 200..299) OpOutcome.SUCCESS else classifyHttpFailure(code)
    }

    private fun executeDelete(
        httpClient: WidgetHttpClient,
        op: PendingTodoOperation
    ): OpOutcome {
        if (op.itemUid.startsWith(PendingSyncStore.LOCAL_UID_PREFIX)) {
            return OpOutcome.SUCCESS
        }

        val body = mapOf(
            "entity_id" to op.entityId,
            "item" to op.itemUid
        )
        val response = httpClient.post("api/services/todo/remove_item", gson.toJson(body))
            ?: return OpOutcome.TRANSIENT_ERROR

        val code = response.use { resp ->
            if (!resp.isSuccessful) {
                Log.w("HAdo", "Sync DELETE failed: ${resp.code} ${resp.body?.string()}")
            }
            resp.code
        }

        return if (code in 200..299 || code == 404 || code == 400) {
            // If the item was already deleted remotely, treat as success
            OpOutcome.SUCCESS
        } else {
            classifyHttpFailure(code)
        }
    }

    private fun executeReorder(
        httpClient: WidgetHttpClient,
        op: PendingTodoOperation
    ): OpOutcome {
        if (op.itemUid.startsWith(PendingSyncStore.LOCAL_UID_PREFIX)) {
            return OpOutcome.PERMANENT_ERROR
        }
        val prevUid = op.previousUid?.takeUnless { it.startsWith(PendingSyncStore.LOCAL_UID_PREFIX) }
        val ok = httpClient.moveTodoItem(op.entityId, op.itemUid, prevUid)
        return if (ok) {
            OpOutcome.SUCCESS
        } else {
            // Check if server is reachable; if unreachable, retry later
            val ping = httpClient.get("api/")
            val reachable = ping?.use { it.isSuccessful } == true
            if (reachable) OpOutcome.PERMANENT_ERROR else OpOutcome.TRANSIENT_ERROR
        }
    }

    private suspend fun executeMoveToList(
        context: Context,
        accountId: String?,
        httpClient: WidgetHttpClient,
        op: PendingTodoOperation
    ): OpOutcome {
        val targetId = op.targetEntityId ?: return OpOutcome.PERMANENT_ERROR
        val summary = op.summary?.takeIf { it.isNotBlank() } ?: return OpOutcome.PERMANENT_ERROR
        val targetFeatures = op.targetFeatures ?: TodoListFeature.CREATE_TODO_ITEM
        val itemToMove = TodoItem(
            uid = op.itemUid,
            summary = summary,
            status = op.status ?: TodoItemStatus.NEEDS_ACTION,
            description = op.description,
            due = op.due
        )
        val plan = buildMovePayload(itemToMove, targetFeatures)
        val targetUids = fetchListItems(httpClient, gson, targetId)?.map { it.uid }?.toSet()
            ?: return OpOutcome.TRANSIENT_ERROR

        val addBody = mapOf("entity_id" to targetId, "item" to summary) + plan.haFields()
        val addResponse = httpClient.post("api/services/todo/add_item", gson.toJson(addBody))
            ?: return OpOutcome.TRANSIENT_ERROR

        val addCode = addResponse.use { resp ->
            if (!resp.isSuccessful) {
                Log.w("HAdo", "Sync MOVE_TO_LIST add failed: ${resp.code} ${resp.body?.string()}")
            }
            resp.code
        }
        if (addCode !in 200..299) {
            return classifyHttpFailure(addCode)
        }

        val resolved = resolveAddedItem(httpClient, gson, targetId, targetUids, summary)
        if (resolved != null) {
            val newUid = resolved.item.uid
            if (op.position == AddItemPosition.TOP && !itemToMove.isCompleted && !resolved.alreadyFirstActive &&
                TodoListFeature.hasFeature(targetFeatures, TodoListFeature.MOVE_TODO_ITEM)
            ) {
                httpClient.moveTodoItem(targetId, newUid, null)
            }
            val finalItem = if (plan.markCompleted) {
                val body = mapOf("entity_id" to targetId, "item" to newUid, "status" to "completed")
                httpClient.post("api/services/todo/update_item", gson.toJson(body))?.close()
                resolved.item.copy(status = TodoItemStatus.COMPLETED)
            } else {
                resolved.item
            }

            PendingSyncStore.replaceUid(context, accountId, targetId, op.itemUid, newUid)
            TodoCacheManager.replaceItemUid(context, accountId, targetId, op.itemUid, finalItem, notify = true)
            WidgetStateMutator.replaceItem(context, accountId, targetId, op.itemUid, finalItem)
        }

        if (!op.itemUid.startsWith(PendingSyncStore.LOCAL_UID_PREFIX)) {
            val removeBody = mapOf("entity_id" to op.entityId, "item" to op.itemUid)
            httpClient.post("api/services/todo/remove_item", gson.toJson(removeBody))?.close()
        }

        return OpOutcome.SUCCESS
    }

    private fun reconcileEntityFromServer(
        context: Context,
        accountId: String?,
        httpClient: WidgetHttpClient,
        entityId: String
    ) {
        val fresh = fetchListItems(httpClient, gson, entityId) ?: return
        TodoCacheManager.mergeRemoteItemsAndSave(context, accountId, entityId, fresh, notify = true)
    }

    private fun classifyHttpFailure(code: Int): OpOutcome {
        return when {
            code == 408 || code == 429 || code in 500..599 -> OpOutcome.TRANSIENT_ERROR
            code == 401 || code == 403 -> OpOutcome.TRANSIENT_ERROR
            code in 400..499 -> OpOutcome.PERMANENT_ERROR
            else -> OpOutcome.TRANSIENT_ERROR
        }
    }

    private fun resolveAccountId(context: Context, accountId: String?): String? {
        val tm = TokenManager(context)
        if (tm.isDemoMode) return null
        return accountId ?: tm.activeAccountId
    }

    private fun enqueueWidgetRefresh(context: Context, preferredAppWidgetId: Int?) {
        if (preferredAppWidgetId != null) {
            TodoWidgetWorker.enqueueOneTime(context, preferredAppWidgetId)
        } else {
            TodoWidgetWorker.enqueueOneTime(context)
        }
    }
}
