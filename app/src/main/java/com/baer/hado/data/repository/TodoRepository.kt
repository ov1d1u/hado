package com.baer.hado.data.repository

import android.content.Context
import com.baer.hado.data.api.HaApiService
import com.baer.hado.data.local.AddItemPosition
import com.baer.hado.data.local.LocalTodoStore
import com.baer.hado.data.local.TodoCacheManager
import com.baer.hado.data.local.TokenManager
import com.baer.hado.data.model.HaState
import com.baer.hado.data.model.TodoItem
import com.baer.hado.data.model.TodoItemStatus
import com.baer.hado.data.sync.TodoSyncManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.ResponseBody
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TodoRepository @Inject constructor(
    private val apiService: HaApiService,
    private val gson: Gson,
    private val tokenManager: TokenManager,
    @ApplicationContext private val context: Context
) {

    private val localStore by lazy { LocalTodoStore(context) }

    private val activeAccountId: String?
        get() = if (tokenManager.isDemoMode) null else tokenManager.activeAccountId

    fun getCachedTodoLists(): List<HaState> {
        if (tokenManager.isDemoMode) {
            return localStore.getLists()
        }
        return TodoCacheManager.loadLists(context, activeAccountId).orEmpty()
    }

    fun getCachedTodoItems(entityId: String): List<TodoItem>? {
        if (tokenManager.isDemoMode) {
            return localStore.getItems(entityId)
        }
        return TodoCacheManager.getEffectiveItems(context, activeAccountId, entityId)
    }

    suspend fun getTodoLists(): Result<List<HaState>> {
        if (tokenManager.isDemoMode) {
            return Result.success(localStore.getLists())
        }
        val accountId = activeAccountId
        return try {
            val states = apiService.getStates()
            val todoEntities = states.filter { it.entityId.startsWith("todo.") }
            TodoCacheManager.saveLists(context, accountId, todoEntities, notify = false)
            Result.success(todoEntities)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getTodoItems(entityId: String): Result<List<TodoItem>> {
        if (tokenManager.isDemoMode) {
            return Result.success(localStore.getItems(entityId))
        }
        val accountId = activeAccountId
        return try {
            TodoSyncManager.drainQueue(context, accountId)
            val body = mapOf(
                "entity_id" to entityId
            )
            val response = executeTodoItemsRequest(body)

            if (!response.isSuccessful) {
                return Result.failure(Exception("API error: ${response.code()}"))
            }

            val responseBody = response.body()?.string()
                ?: return Result.success(emptyList())

            val parsed = parseItemsResponse(responseBody, entityId)
            val effective = TodoCacheManager.mergeRemoteItemsAndSave(
                context = context,
                accountId = accountId,
                entityId = entityId,
                remoteItems = parsed,
                notify = false
            )
            Result.success(effective)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun executeTodoItemsRequest(body: Map<String, String>): Response<ResponseBody> {
        val response = apiService.getTodoItems(body)
        if (response.code() != 429) {
            return response
        }

        val retryAfterSeconds = response.headers()["Retry-After"]?.trim()?.toLongOrNull()
        if (retryAfterSeconds == null || retryAfterSeconds <= 0 || retryAfterSeconds > 5) {
            return response
        }

        response.body()?.close()
        response.errorBody()?.close()
        delay(retryAfterSeconds * 1000)
        return apiService.getTodoItems(body)
    }

    private fun parseItemsResponse(json: String, entityId: String): List<TodoItem> {
        return try {
            // Response format: { "service_response": { "entity_id": { "items": [...] } } }
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val root: Map<String, Any> = gson.fromJson(json, type)
            val serviceResponse = root["service_response"] as? Map<*, *> ?: root
            val entityData = serviceResponse[entityId] as? Map<*, *> ?: return emptyList()
            val itemsList = entityData["items"] ?: return emptyList()
            val itemsJson = gson.toJson(itemsList)
            val itemsType = object : TypeToken<List<TodoItem>>() {}.type
            gson.fromJson(itemsJson, itemsType)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun createList(name: String): HaState {
        return localStore.createList(name)
    }

    fun deleteList(entityId: String) {
        localStore.deleteList(entityId)
    }

    suspend fun addItem(
        entityId: String,
        summary: String,
        description: String? = null,
        dueDate: String? = null,
        dueDatetime: String? = null,
        position: AddItemPosition = AddItemPosition.TOP
    ): Result<Unit> {
        return try {
            TodoSyncManager.addItem(
                context = context,
                accountId = activeAccountId,
                entityId = entityId,
                summary = summary,
                description = description,
                due = dueDatetime ?: dueDate,
                position = position,
                isLocalMode = tokenManager.isDemoMode
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateItemStatus(
        entityId: String,
        itemUid: String,
        completed: Boolean
    ): Result<Unit> {
        return try {
            TodoSyncManager.toggleItem(
                context = context,
                accountId = activeAccountId,
                entityId = entityId,
                itemUid = itemUid,
                completed = completed,
                isLocalMode = tokenManager.isDemoMode
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun renameItem(
        entityId: String,
        itemUid: String,
        newName: String
    ): Result<Unit> {
        return updateItemDetails(entityId, itemUid, rename = newName)
    }

    suspend fun updateItemDetails(
        entityId: String,
        itemUid: String,
        rename: String? = null,
        status: String? = null,
        description: String? = null,
        dueDate: String? = null,
        dueDatetime: String? = null
    ): Result<Unit> {
        return try {
            val existing = getCachedTodoItems(entityId)?.firstOrNull { it.uid == itemUid }
                ?: TodoItem(
                    uid = itemUid,
                    summary = rename ?: "",
                    status = TodoItemStatus.NEEDS_ACTION
                )
            val updated = existing.copy(
                summary = rename ?: existing.summary,
                status = status?.let {
                    if (it == "completed") TodoItemStatus.COMPLETED else TodoItemStatus.NEEDS_ACTION
                } ?: existing.status,
                description = description ?: existing.description,
                due = (dueDatetime ?: dueDate) ?: existing.due
            )
            TodoSyncManager.updateItemDetails(
                context = context,
                accountId = activeAccountId,
                entityId = entityId,
                original = existing,
                updated = updated,
                isLocalMode = tokenManager.isDemoMode
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeItem(entityId: String, itemUid: String): Result<Unit> {
        return try {
            TodoSyncManager.deleteItem(
                context = context,
                accountId = activeAccountId,
                entityId = entityId,
                itemUid = itemUid,
                isLocalMode = tokenManager.isDemoMode
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
