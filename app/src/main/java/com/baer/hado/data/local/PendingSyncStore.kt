package com.baer.hado.data.local

import android.content.Context
import com.baer.hado.data.model.PendingOperationType
import com.baer.hado.data.model.PendingTodoOperation
import com.baer.hado.data.model.TodoItem
import com.baer.hado.data.model.TodoItemStatus
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object PendingSyncStore {
    private const val PREFS_NAME = "hado_pending_sync"
    private const val KEY_QUEUE_PREFIX = "queue_"

    private val gson = Gson()
    private val queueType = object : TypeToken<List<PendingTodoOperation>>() {}.type
    private val lock = Any()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun queueKey(accountId: String?): String =
        KEY_QUEUE_PREFIX + (accountId ?: TokenManager.PRIMARY_ACCOUNT_ID)

    fun loadQueue(context: Context, accountId: String?): List<PendingTodoOperation> {
        synchronized(lock) {
            val json = prefs(context).getString(queueKey(accountId), null) ?: return emptyList()
            return try {
                gson.fromJson<List<PendingTodoOperation>>(json, queueType) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private fun saveQueueLocked(context: Context, accountId: String?, queue: List<PendingTodoOperation>) {
        prefs(context).edit()
            .putString(queueKey(accountId), gson.toJson(queue))
            .apply()
    }

    fun hasPendingOperations(context: Context, accountId: String?): Boolean {
        return loadQueue(context, accountId).isNotEmpty()
    }

    fun enqueueAdd(
        context: Context,
        accountId: String?,
        entityId: String,
        item: TodoItem,
        position: AddItemPosition
    ) {
        synchronized(lock) {
            val queue = loadQueue(context, accountId).toMutableList()
            queue.add(
                PendingTodoOperation(
                    type = PendingOperationType.ADD,
                    entityId = entityId,
                    itemUid = item.uid,
                    summary = item.summary,
                    description = item.description,
                    hasDescriptionUpdate = item.description != null,
                    due = item.due,
                    hasDueUpdate = item.due != null,
                    status = item.status,
                    position = position
                )
            )
            saveQueueLocked(context, accountId, queue)
        }
    }

    fun enqueueToggleStatus(
        context: Context,
        accountId: String?,
        entityId: String,
        itemUid: String,
        newStatus: TodoItemStatus
    ) {
        synchronized(lock) {
            val queue = loadQueue(context, accountId).toMutableList()
            // If there's an un-synced ADD for this item, update its status directly
            val addIdx = queue.indexOfFirst {
                it.type == PendingOperationType.ADD && it.entityId == entityId && it.itemUid == itemUid
            }
            if (addIdx >= 0) {
                queue[addIdx] = queue[addIdx].copy(status = newStatus)
                saveQueueLocked(context, accountId, queue)
                return
            }

            // If there's a pending MOVE_TO_LIST where this item was moved to entityId, update its status
            val moveIdx = queue.indexOfFirst {
                it.type == PendingOperationType.MOVE_TO_LIST && it.targetEntityId == entityId && it.itemUid == itemUid
            }
            if (moveIdx >= 0) {
                queue[moveIdx] = queue[moveIdx].copy(status = newStatus)
                saveQueueLocked(context, accountId, queue)
                return
            }

            // Merge with existing UPDATE for this item if present
            val updateIdx = queue.indexOfLast {
                it.type == PendingOperationType.UPDATE && it.entityId == entityId && it.itemUid == itemUid
            }
            if (updateIdx >= 0) {
                queue[updateIdx] = queue[updateIdx].copy(status = newStatus)
            } else {
                queue.add(
                    PendingTodoOperation(
                        type = PendingOperationType.UPDATE,
                        entityId = entityId,
                        itemUid = itemUid,
                        status = newStatus
                    )
                )
            }
            saveQueueLocked(context, accountId, queue)
        }
    }

    fun enqueueUpdateDetails(
        context: Context,
        accountId: String?,
        entityId: String,
        original: TodoItem,
        updated: TodoItem
    ) {
        synchronized(lock) {
            val queue = loadQueue(context, accountId).toMutableList()
            val itemUid = original.uid

            // If there's an un-synced ADD for this item, update its fields in place
            val addIdx = queue.indexOfFirst {
                it.type == PendingOperationType.ADD && it.entityId == entityId && it.itemUid == itemUid
            }
            if (addIdx >= 0) {
                queue[addIdx] = queue[addIdx].copy(
                    summary = updated.summary,
                    description = updated.description,
                    hasDescriptionUpdate = updated.description != null,
                    due = updated.due,
                    hasDueUpdate = updated.due != null,
                    status = updated.status
                )
                saveQueueLocked(context, accountId, queue)
                return
            }

            // If there's a pending MOVE_TO_LIST to this entityId, update its fields in place
            val moveIdx = queue.indexOfFirst {
                it.type == PendingOperationType.MOVE_TO_LIST && it.targetEntityId == entityId && it.itemUid == itemUid
            }
            if (moveIdx >= 0) {
                queue[moveIdx] = queue[moveIdx].copy(
                    summary = updated.summary,
                    description = updated.description,
                    hasDescriptionUpdate = true,
                    due = updated.due,
                    hasDueUpdate = true,
                    status = updated.status
                )
                saveQueueLocked(context, accountId, queue)
                return
            }

            val summaryChanged = updated.summary != original.summary
            val descChanged = updated.description != original.description
            val dueChanged = updated.due != original.due
            val statusChanged = updated.status != original.status
            if (!summaryChanged && !descChanged && !dueChanged && !statusChanged) return

            val updateIdx = queue.indexOfLast {
                it.type == PendingOperationType.UPDATE && it.entityId == entityId && it.itemUid == itemUid
            }
            if (updateIdx >= 0) {
                val prev = queue[updateIdx]
                queue[updateIdx] = prev.copy(
                    summary = if (summaryChanged) updated.summary else prev.summary,
                    description = if (descChanged) updated.description else prev.description,
                    hasDescriptionUpdate = prev.hasDescriptionUpdate || descChanged,
                    due = if (dueChanged) updated.due else prev.due,
                    hasDueUpdate = prev.hasDueUpdate || dueChanged,
                    status = if (statusChanged) updated.status else prev.status
                )
            } else {
                queue.add(
                    PendingTodoOperation(
                        type = PendingOperationType.UPDATE,
                        entityId = entityId,
                        itemUid = itemUid,
                        summary = if (summaryChanged) updated.summary else null,
                        description = if (descChanged) updated.description else null,
                        hasDescriptionUpdate = descChanged,
                        due = if (dueChanged) updated.due else null,
                        hasDueUpdate = dueChanged,
                        status = if (statusChanged) updated.status else null
                    )
                )
            }
            saveQueueLocked(context, accountId, queue)
        }
    }

    fun enqueueDelete(
        context: Context,
        accountId: String?,
        entityId: String,
        itemUid: String
    ) {
        synchronized(lock) {
            val queue = loadQueue(context, accountId).toMutableList()
            // If there's an un-synced ADD for this item, cancel the ADD and any subsequent ops for it
            val hadAdd = queue.removeAll {
                (it.type == PendingOperationType.ADD && it.entityId == entityId && it.itemUid == itemUid) ||
                    (it.entityId == entityId && it.itemUid == itemUid)
            }
            // Rewrite any REORDER that referenced this deleted item as previousUid
            // (not strictly required since the caller passes the remaining item's previousUid, but clean)
            if (hadAdd && itemUid.startsWith(LOCAL_UID_PREFIX)) {
                saveQueueLocked(context, accountId, queue)
                return
            }

            // If a pending MOVE_TO_LIST moved this item to entityId, convert that MOVE_TO_LIST into a DELETE on source
            val moveIdx = queue.indexOfFirst {
                it.type == PendingOperationType.MOVE_TO_LIST && it.targetEntityId == entityId && it.itemUid == itemUid
            }
            if (moveIdx >= 0) {
                val moveOp = queue[moveIdx]
                queue[moveIdx] = PendingTodoOperation(
                    type = PendingOperationType.DELETE,
                    entityId = moveOp.entityId,
                    itemUid = moveOp.itemUid
                )
                saveQueueLocked(context, accountId, queue)
                return
            }

            // Remove any pending UPDATE or REORDER for this item before adding DELETE
            queue.removeAll {
                (it.type == PendingOperationType.UPDATE || it.type == PendingOperationType.REORDER) &&
                    it.entityId == entityId && it.itemUid == itemUid
            }
            queue.add(
                PendingTodoOperation(
                    type = PendingOperationType.DELETE,
                    entityId = entityId,
                    itemUid = itemUid
                )
            )
            saveQueueLocked(context, accountId, queue)
        }
    }

    fun enqueueReorder(
        context: Context,
        accountId: String?,
        entityId: String,
        itemUid: String,
        previousUid: String?
    ) {
        synchronized(lock) {
            val queue = loadQueue(context, accountId).toMutableList()
            // Remove any earlier REORDER for the same item in the same list so only the latest order stands
            queue.removeAll {
                it.type == PendingOperationType.REORDER && it.entityId == entityId && it.itemUid == itemUid
            }
            queue.add(
                PendingTodoOperation(
                    type = PendingOperationType.REORDER,
                    entityId = entityId,
                    itemUid = itemUid,
                    previousUid = previousUid
                )
            )
            saveQueueLocked(context, accountId, queue)
        }
    }

    fun enqueueMoveToList(
        context: Context,
        accountId: String?,
        fromEntityId: String,
        toEntityId: String,
        targetFeatures: Int,
        movedItem: TodoItem,
        position: AddItemPosition
    ) {
        synchronized(lock) {
            val queue = loadQueue(context, accountId).toMutableList()
            val itemUid = movedItem.uid

            // If this item is an un-synced ADD in fromEntityId, just retarget the ADD to toEntityId!
            val addIdx = queue.indexOfFirst {
                it.type == PendingOperationType.ADD && it.entityId == fromEntityId && it.itemUid == itemUid
            }
            if (addIdx >= 0) {
                queue.removeAll {
                    it.type != PendingOperationType.ADD && it.entityId == fromEntityId && it.itemUid == itemUid
                }
                val newAddIdx = queue.indexOfFirst {
                    it.type == PendingOperationType.ADD && it.entityId == fromEntityId && it.itemUid == itemUid
                }
                if (newAddIdx >= 0) {
                    queue[newAddIdx] = queue[newAddIdx].copy(
                        entityId = toEntityId,
                        summary = movedItem.summary,
                        description = movedItem.description,
                        hasDescriptionUpdate = movedItem.description != null,
                        due = movedItem.due,
                        hasDueUpdate = movedItem.due != null,
                        status = movedItem.status,
                        position = position
                    )
                }
                saveQueueLocked(context, accountId, queue)
                return
            }

            // Remove any pending UPDATE or REORDER on the source list since MOVE_TO_LIST carries the full mapped snapshot
            queue.removeAll {
                (it.type == PendingOperationType.UPDATE || it.type == PendingOperationType.REORDER) &&
                    it.entityId == fromEntityId && it.itemUid == itemUid
            }
            queue.add(
                PendingTodoOperation(
                    type = PendingOperationType.MOVE_TO_LIST,
                    entityId = fromEntityId,
                    targetEntityId = toEntityId,
                    targetFeatures = targetFeatures,
                    itemUid = itemUid,
                    summary = movedItem.summary,
                    description = movedItem.description,
                    hasDescriptionUpdate = movedItem.description != null,
                    due = movedItem.due,
                    hasDueUpdate = movedItem.due != null,
                    status = movedItem.status,
                    position = position
                )
            )
            saveQueueLocked(context, accountId, queue)
        }
    }

    fun removeOperation(context: Context, accountId: String?, operationId: String) {
        synchronized(lock) {
            val queue = loadQueue(context, accountId).filterNot { it.id == operationId }
            saveQueueLocked(context, accountId, queue)
        }
    }

    /**
     * Rewrites [oldUid] to [newUid] across all remaining operations in the queue for [entityId].
     */
    fun replaceUid(context: Context, accountId: String?, entityId: String, oldUid: String, newUid: String) {
        if (oldUid == newUid) return
        synchronized(lock) {
            val queue = loadQueue(context, accountId).map { op ->
                if (op.entityId == entityId || op.targetEntityId == entityId) {
                    op.copy(
                        itemUid = if (op.itemUid == oldUid) newUid else op.itemUid,
                        previousUid = if (op.previousUid == oldUid) newUid else op.previousUid
                    )
                } else {
                    op
                }
            }
            saveQueueLocked(context, accountId, queue)
        }
    }

    fun clearAccount(context: Context, accountId: String?) {
        synchronized(lock) {
            prefs(context).edit().remove(queueKey(accountId)).apply()
        }
    }

    fun clearAll(context: Context) {
        synchronized(lock) {
            prefs(context).edit().clear().apply()
        }
    }

    const val LOCAL_UID_PREFIX = "local_"
}
