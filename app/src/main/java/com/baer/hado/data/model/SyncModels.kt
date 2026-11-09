package com.baer.hado.data.model

import com.baer.hado.data.local.AddItemPosition
import java.util.UUID

enum class PendingOperationType {
    ADD,
    UPDATE,
    DELETE,
    REORDER,
    MOVE_TO_LIST
}

data class PendingTodoOperation(
    val id: String = UUID.randomUUID().toString(),
    val type: PendingOperationType,
    val entityId: String,
    val itemUid: String,
    // ADD & MOVE_TO_LIST fields
    val summary: String? = null,
    val description: String? = null,
    val hasDescriptionUpdate: Boolean = false,
    val due: String? = null,
    val hasDueUpdate: Boolean = false,
    val status: TodoItemStatus? = null,
    val position: AddItemPosition = AddItemPosition.TOP,
    // REORDER fields
    val previousUid: String? = null,
    // MOVE_TO_LIST fields
    val targetEntityId: String? = null,
    val targetFeatures: Int? = null,
    val createdAt: Long = System.currentTimeMillis()
)
