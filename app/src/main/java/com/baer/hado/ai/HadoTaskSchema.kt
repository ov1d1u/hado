@file:OptIn(ExperimentalAppFunctionsApi::class)

package com.baer.hado.ai

import androidx.appfunctions.AppFunctionSchemaDefinition
import androidx.appfunctions.ExperimentalAppFunctionsApi
import com.google.android.appfunctions.schema.common.v1.tasks.CreateTaskParams
import com.google.android.appfunctions.schema.common.v1.tasks.Task

/**
 * The platform's `tasks.createTask` schema.
 *
 * Assistants such as Gemini only offer an app for a "remind me" request when the app implements
 * this published schema, so the signature, the annotation values and the document types must
 * match it exactly.
 */
@AppFunctionSchemaDefinition(name = "createTask", version = 1, category = "tasks")
interface HadoTaskSchema {

    /** Creates a task in one of the user's HAdo to-do lists. */
    suspend fun createTask(params: CreateTaskParams): Task
}
