@file:OptIn(ExperimentalAppFunctionsApi::class)

package com.baer.hado.ai

import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionDisabledException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import androidx.appfunctions.ExperimentalAppFunctionsApi
import com.baer.hado.data.local.AppPreferencesManager
import com.baer.hado.data.local.TokenManager
import com.baer.hado.data.model.HaState
import com.baer.hado.data.model.TodoListFeature
import com.baer.hado.data.repository.TodoRepository
import com.baer.hado.widget.TodoWidgetWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** A to-do list of the user that HAdo shows. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class TodoListInfo(
    /** The name of the list as the user sees it, such as "Groceries". */
    val name: String,
)

/** The task that was added to one of the user's to-do lists. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class AddedTask(
    /** The title of the task. */
    val title: String,
    /** The name of the to-do list the task was added to. */
    val listName: String,
    /** The day the task is due, or null when no due date was set. */
    val dueDate: LocalDate?,
    /** The time of day the task is due, or null when no time was set. */
    val dueTime: LocalTime?,
)

@RequiresApi(36)
@AndroidEntryPoint
@AppFunctionServiceEntryPoint(
    serviceName = "HadoAppFunctionService",
    appFunctionXmlFileName = "hado_app_function_service",
)
abstract class BaseHadoAppFunctionService : AppFunctionService() {

    @Inject internal lateinit var todoRepository: TodoRepository

    @Inject internal lateinit var tokenManager: TokenManager

    /**
     * Adds a task to one of the user's to-do lists in HAdo.
     *
     * Use this when the user asks to add, create or remember a task, reminder or to-do item.
     * Pass listName only when the user names a list; without it the task goes to the
     * user's first list. Only lists the user shows in HAdo can be used.
     *
     * @param title The task to add, as short text such as "Buy milk".
     * @param listName The name of the list to add the task to, as the user said it.
     * @param dueDate The day the task is due, when the user mentions one.
     * @param dueTime The time of day the task is due, when the user mentions one. Needs dueDate too.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun createTask(
        title: String,
        listName: String? = null,
        dueDate: LocalDate? = null,
        dueTime: LocalTime? = null,
    ): AddedTask = withContext(Dispatchers.IO) {
        requireSignedIn()
        val taskTitle = title.trim()
        if (taskTitle.isEmpty()) {
            throw AppFunctionInvalidArgumentException("The task needs a title.")
        }
        if (dueTime != null && dueDate == null) {
            throw AppFunctionInvalidArgumentException("A due time needs a due date too.")
        }

        val list = findList(visibleLists(), listName)
        val name = list.displayName()
        val features = list.attributes.supportedFeatures ?: 0
        fun supports(feature: Int) = TodoListFeature.hasFeature(features, feature)

        if (!supports(TodoListFeature.CREATE_TODO_ITEM)) {
            throw AppFunctionInvalidArgumentException("\"$name\" does not accept new tasks.")
        }
        if (dueDate != null && dueTime == null && !supports(TodoListFeature.SET_DUE_DATE_ON_ITEM)) {
            throw AppFunctionInvalidArgumentException("\"$name\" does not support due dates.")
        }
        if (dueTime != null && !supports(TodoListFeature.SET_DUE_DATETIME_ON_ITEM)) {
            throw AppFunctionInvalidArgumentException("\"$name\" does not support due times.")
        }

        val dueDatetime = if (dueDate != null && dueTime != null) {
            LocalDateTime.of(dueDate, dueTime).format(DATETIME_FORMAT)
        } else {
            null
        }
        val dueDateOnly = if (dueTime == null) dueDate?.toString() else null
        todoRepository.addItem(
            entityId = list.entityId,
            summary = taskTitle,
            dueDate = dueDateOnly,
            dueDatetime = dueDatetime,
            position = AppPreferencesManager.loadAddItemPosition(applicationContext)
        ).getOrElse { e ->
            throw AppFunctionAppUnknownException(
                "The task could not be added to \"$name\": ${e.message}"
            )
        }
        TodoWidgetWorker.enqueueOneTime(applicationContext)

        AddedTask(
            title = taskTitle,
            listName = name,
            dueDate = dueDate,
            dueTime = dueTime,
        )
    }

    /**
     * Lists the names of the to-do lists the user shows in HAdo, in the order they appear there.
     *
     * Use this when the user asks which lists exist, or to find the exact name of a list
     * before adding a task to it.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun listTodoLists(): List<TodoListInfo> = withContext(Dispatchers.IO) {
        requireSignedIn()
        visibleLists().map { TodoListInfo(name = it.displayName()) }
    }

    private fun requireSignedIn() {
        if (!tokenManager.isLoggedIn) {
            throw AppFunctionDisabledException(
                "HAdo is not signed in to a Home Assistant server. Open HAdo and sign in first."
            )
        }
    }

    /** The lists the user shows in HAdo, in the order they appear there. */
    private suspend fun visibleLists(): List<HaState> {
        val accountId = if (tokenManager.isDemoMode) null else tokenManager.activeAccountId
        val lists = todoRepository.getTodoLists().getOrElse { e ->
            throw AppFunctionAppUnknownException("Could not load the to-do lists: ${e.message}")
        }
        val hidden = AppPreferencesManager.loadHiddenLists(applicationContext, accountId)
        val order = AppPreferencesManager.loadListOrder(applicationContext, accountId)
            .withIndex()
            .associate { (index, id) -> id to index }
        return lists
            .filterNot { it.entityId in hidden }
            .sortedBy { order[it.entityId] ?: Int.MAX_VALUE }
    }

    /** The list named [requested], or the first list when no name is given. */
    private fun findList(lists: List<HaState>, requested: String?): HaState {
        if (lists.isEmpty()) {
            throw AppFunctionInvalidArgumentException(
                "HAdo shows no to-do list. Show a list in HAdo's settings first."
            )
        }
        val wanted = requested?.trim()
        if (wanted.isNullOrEmpty()) return lists.first()

        lists.firstOrNull { it.displayName().equals(wanted, ignoreCase = true) }?.let { return it }

        val partial = lists.filter { it.displayName().contains(wanted, ignoreCase = true) }
        if (partial.size == 1) return partial.single()

        if (partial.size > 1) {
            throw AppFunctionInvalidArgumentException(
                "\"$wanted\" matches more than one list: ${partial.joinToString { it.displayName() }}."
            )
        }
        throw AppFunctionInvalidArgumentException(
            "There is no list named \"$wanted\". The lists are: ${lists.joinToString { it.displayName() }}."
        )
    }

    private fun HaState.displayName(): String = attributes.friendlyName ?: entityId

    private companion object {
        val DATETIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
