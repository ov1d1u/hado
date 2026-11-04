package com.google.android.appfunctions.schema.common.v1.tasks

import androidx.appfunctions.AppFunctionSerializable
import com.google.android.appfunctions.schema.common.v1.types.DateTime

/**
 * The parameters of the platform's `tasks.createTask` schema.
 *
 * This mirrors the platform's type, so it must keep this package and these property names for
 * assistants to recognise it as a task-creation request.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class CreateTaskParams(
    /** The title of the task, such as "Buy milk". */
    val title: String,
    /** Extra detail about the task. */
    val description: String? = null,
    /** Whether the task is already done. */
    val completed: Boolean? = null,
    /** When the task is due. */
    val dateTime: DateTime? = null,
    /** Whether the task is due on a whole day rather than at a specific time. */
    val allDay: Boolean? = null,
    /** The recurrence rule of the task, in iCalendar format. */
    val recurrenceSchedule: String? = null,
    /** An identifier of the task in the calling app, when it has one. */
    val externalId: String? = null,
    /** The identifier of the task category the task belongs to. */
    val taskCategoryId: String? = null,
)
