package com.baer.hado.ui.home

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baer.hado.data.local.AddItemPosition
import com.baer.hado.data.local.AppPreferencesManager
import com.baer.hado.data.local.TodoCacheManager
import com.baer.hado.data.local.TokenManager
import com.baer.hado.data.model.HaState
import com.baer.hado.data.model.TodoItem
import com.baer.hado.data.repository.AuthRepository
import com.baer.hado.data.repository.TodoRepository
import com.baer.hado.data.sync.TodoSyncManager
import com.baer.hado.notifications.OverdueNotificationScheduler
import com.baer.hado.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val todoLists: List<HaState> = emptyList(),
    val selectedListId: String? = null,
    val itemsByList: Map<String, List<TodoItem>> = emptyMap(),
    val loadingListIds: Set<String> = emptySet(),
    val lastLoadedAt: Map<String, Long> = emptyMap(),
    val isLoadingLists: Boolean = false,
    val error: String? = null,
    val isLoggedOut: Boolean = false,
    val isLocalMode: Boolean = false,
    /** id to display name of every HA server, in settings order. */
    val servers: List<Pair<String, String>> = emptyList(),
    val activeServerId: String? = null,
    /** Set when the active server changed and the screen must reload. */
    val serverChanged: Boolean = false,
    /** True when the server has lists but the settings filter hides all of them. */
    val allListsHidden: Boolean = false
) {
    fun itemsFor(entityId: String?): List<TodoItem> {
        return entityId?.let { itemsByList[it] }.orEmpty()
    }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val todoRepository: TodoRepository,
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager,
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    companion object {
        private const val SELECTED_LIST_CACHE_TTL_MS = 5_000L
        private const val BACKGROUND_LIST_CACHE_TTL_MS = 15_000L
    }

    /** Prefs for list order and visibility belong to the server shown in the app. */
    private val scopeAccountId: String?
        get() = if (tokenManager.isDemoMode) null else tokenManager.activeAccountId

    private fun applyOrder(lists: List<HaState>): List<HaState> {
        val saved = AppPreferencesManager.loadListOrder(appContext, scopeAccountId)
        if (saved.isEmpty()) return lists
        val orderMap = saved.withIndex().associate { (i, id) -> id to i }
        return lists.sortedBy { orderMap[it.entityId] ?: Int.MAX_VALUE }
    }

    fun reorderLists(fromIndex: Int, toIndex: Int) {
        val current = _uiState.value.todoLists.toMutableList()
        if (fromIndex !in current.indices || toIndex !in current.indices) return
        val item = current.removeAt(fromIndex)
        current.add(toIndex, item)
        _uiState.value = _uiState.value.copy(todoLists = current)
        // Only the shown lists can be dragged; keep the hidden ones in the saved order.
        val shownOrder = current.map { it.entityId }
        val saved = AppPreferencesManager.loadListOrder(appContext, scopeAccountId)
        AppPreferencesManager.saveListOrder(appContext, scopeAccountId, shownOrder + saved.filterNot { it in shownOrder })
    }

    private fun applyVisibility(lists: List<HaState>): List<HaState> {
        val hidden = AppPreferencesManager.loadHiddenLists(appContext, scopeAccountId)
        if (hidden.isEmpty()) return lists
        return lists.filterNot { it.entityId in hidden }
    }

    /** Re-applies the settings filter to the lists already fetched, e.g. on return from settings. */
    fun refreshVisibleLists() {
        val all = allTodoLists
        if (all.isEmpty()) return
        val shown = applyVisibility(applyOrder(all))
        if (shown.map { it.entityId } == _uiState.value.todoLists.map { it.entityId }) return
        publishLists(all, shown)
    }

    /**
     * Stores the fetched lists, shows [shown] and keeps the selection on a shown list,
     * falling back to [preferredId] and then to the first shown list.
     */
    private fun publishLists(
        all: List<HaState>,
        shown: List<HaState>,
        preferredId: String? = null,
        fetchSelected: Boolean = true
    ) {
        allTodoLists = all
        val selectedId = (preferredId ?: _uiState.value.selectedListId)
            ?.takeIf { id -> shown.any { it.entityId == id } }
            ?: shown.firstOrNull()?.entityId

        // Populate itemsByList from local cache for any list not yet in memory
        val currentItems = _uiState.value.itemsByList.toMutableMap()
        for (list in shown) {
            todoRepository.getCachedTodoItems(list.entityId)?.let { cached ->
                currentItems[list.entityId] = cached
            }
        }

        _uiState.value = _uiState.value.copy(
            todoLists = shown,
            selectedListId = selectedId,
            itemsByList = currentItems,
            allListsHidden = all.isNotEmpty() && shown.isEmpty(),
            isLoadingLists = false
        )

        if (fetchSelected) {
            selectedId?.let { loadItems(it) }
        }
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Every list of the active server, before order and the visibility filter. */
    private var allTodoLists: List<HaState> = emptyList()

    private var loadItemsJob: Job? = null
    private var loadingEntityId: String? = null
    private var activeLoadToken = 0L

    /** List asked for by a deep link; applied as soon as it is known to exist, then cleared. */
    private var requestedListId: String? = savedStateHandle[Routes.ARG_LIST]

    init {
        _uiState.value = _uiState.value.copy(isLocalMode = tokenManager.isDemoMode)
        refreshServers()
        loadInitialCache()
        observeCacheUpdates()
        loadTodoLists()
    }

    private fun loadInitialCache() {
        val cachedLists = todoRepository.getCachedTodoLists()
        if (cachedLists.isEmpty()) return
        val ordered = applyOrder(cachedLists)
        val requested = requestedListId?.takeIf { id -> ordered.any { it.entityId == id } }
        val visible = applyVisibility(ordered)
        val shown = if (requested != null && visible.none { it.entityId == requested }) {
            val visibleIds = visible.mapTo(mutableSetOf()) { it.entityId }
            ordered.filter { it.entityId == requested || it.entityId in visibleIds }
        } else {
            visible
        }
        publishLists(all = ordered, shown = shown, preferredId = requested, fetchSelected = false)
    }

    private fun observeCacheUpdates() {
        viewModelScope.launch {
            TodoCacheManager.updates.collect { event ->
                if (event.accountId != scopeAccountId) return@collect
                val entityId = event.entityId
                if (entityId != null) {
                    val effective = todoRepository.getCachedTodoItems(entityId) ?: return@collect
                    _uiState.update { state ->
                        if (state.itemsByList[entityId] == effective) {
                            state
                        } else {
                            state.copy(itemsByList = state.itemsByList + (entityId to effective))
                        }
                    }
                } else {
                    val cachedLists = todoRepository.getCachedTodoLists()
                    if (cachedLists.isNotEmpty()) {
                        val ordered = applyOrder(cachedLists)
                        val visible = applyVisibility(ordered)
                        publishLists(all = ordered, shown = visible, fetchSelected = false)
                    }
                }
            }
        }
    }

    /** Settings can rename or remove servers while Home stays on the back stack. */
    fun refreshServers() {
        _uiState.value = _uiState.value.copy(
            servers = tokenManager.accounts.map { it.id to it.displayName },
            activeServerId = tokenManager.activeAccountId.takeUnless { tokenManager.isDemoMode }
        )
    }

    fun loadTodoLists() {
        viewModelScope.launch {
            val hasCachedLists = _uiState.value.todoLists.isNotEmpty() || allTodoLists.isNotEmpty()
            _uiState.value = _uiState.value.copy(isLoadingLists = !hasCachedLists, error = null)
            todoRepository.getTodoLists().fold(
                onSuccess = { lists ->
                    val ordered = applyOrder(lists)
                    val requested = requestedListId?.takeIf { id -> ordered.any { it.entityId == id } }
                    requestedListId = null
                    val visible = applyVisibility(ordered)
                    // A deep link to a hidden list wins for this session, without touching settings.
                    val shown = if (requested != null && visible.none { it.entityId == requested }) {
                        val visibleIds = visible.mapTo(mutableSetOf()) { it.entityId }
                        ordered.filter { it.entityId == requested || it.entityId in visibleIds }
                    } else {
                        visible
                    }

                    publishLists(all = ordered, shown = shown, preferredId = requested)
                },
                onFailure = { e ->
                    if (e.message?.contains("401") == true) {
                        Log.w("HAdo", "401 on loadTodoLists — token refresh failed, redirecting to login")
                        handleUnauthorized(isLoadingLists = false)
                    } else if (hasCachedLists) {
                        // Keep showing cached lists offline without an error snackbar on startup
                        _uiState.value = _uiState.value.copy(isLoadingLists = false)
                        _uiState.value.selectedListId?.let { loadItems(it) }
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isLoadingLists = false,
                            error = e.message ?: "Failed to load lists"
                        )
                    }
                }
            )
        }
    }

    fun selectList(entityId: String) {
        if (_uiState.value.selectedListId == entityId) return
        val cached = todoRepository.getCachedTodoItems(entityId)
        _uiState.update { state ->
            state.copy(
                selectedListId = entityId,
                itemsByList = if (cached != null) state.itemsByList + (entityId to cached) else state.itemsByList,
                error = null
            )
        }
        loadItems(entityId)
    }

    fun loadItems(entityId: String? = _uiState.value.selectedListId, force: Boolean = false) {
        val id = entityId ?: return
        // Immediately refresh from local cache in case an external activity wrote to it
        todoRepository.getCachedTodoItems(id)?.let { cached ->
            _uiState.update { state ->
                if (state.itemsByList[id] == cached) state
                else state.copy(itemsByList = state.itemsByList + (id to cached))
            }
        }
        if (!shouldLoadItems(id, force)) return

        val previousLoadingId = loadingEntityId
        if (previousLoadingId != null) {
            loadItemsJob?.cancel()
            if (previousLoadingId != id) {
                setLoading(previousLoadingId, false)
            }
        }

        val loadToken = ++activeLoadToken
        loadingEntityId = id
        setLoading(id, true)
        _uiState.update { it.copy(error = null) }

        loadItemsJob = viewModelScope.launch {
            try {
                todoRepository.getTodoItems(id).fold(
                    onSuccess = { items ->
                        if (loadToken != activeLoadToken) return@fold
                        _uiState.update { state ->
                            state.copy(
                                itemsByList = state.itemsByList + (id to items),
                                lastLoadedAt = state.lastLoadedAt + (id to System.currentTimeMillis())
                            )
                        }
                    },
                    onFailure = { e ->
                        if (loadToken != activeLoadToken) return@fold
                        if (e.message?.contains("401") == true) {
                            Log.w("HAdo", "401 on loadItems — token refresh failed, redirecting to login")
                            handleUnauthorized()
                        } else if (force && _uiState.value.itemsByList[id] == null) {
                            _uiState.update { state ->
                                state.copy(error = e.message ?: "Failed to load items")
                            }
                        }
                    }
                )
            } finally {
                if (loadToken == activeLoadToken) {
                    loadingEntityId = null
                    setLoading(id, false)
                }
            }
        }
    }

    fun updateCachedItems(entityId: String, items: List<TodoItem>) {
        _uiState.update { state ->
            if (state.itemsByList[entityId] == items) {
                state
            } else {
                state.copy(itemsByList = state.itemsByList + (entityId to items))
            }
        }
    }

    private fun shouldLoadItems(entityId: String, force: Boolean): Boolean {
        val state = _uiState.value
        if (force) return true
        if (entityId in state.loadingListIds) return false

        val lastLoadedAt = state.lastLoadedAt[entityId] ?: return true
        val ttlMs = if (entityId == state.selectedListId) {
            SELECTED_LIST_CACHE_TTL_MS
        } else {
            BACKGROUND_LIST_CACHE_TTL_MS
        }
        return System.currentTimeMillis() - lastLoadedAt >= ttlMs
    }

    private fun setLoading(entityId: String, isLoading: Boolean) {
        _uiState.update { state ->
            state.copy(
                loadingListIds = if (isLoading) {
                    state.loadingListIds + entityId
                } else {
                    state.loadingListIds - entityId
                }
            )
        }
    }

    fun addItem(summary: String) {
        val entityId = _uiState.value.selectedListId ?: return
        val position = AppPreferencesManager.loadAddItemPosition(appContext)
        viewModelScope.launch {
            todoRepository.addItem(entityId, summary, position = position).fold(
                onSuccess = { loadItems(entityId, force = true) },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
            )
        }
    }

    /**
     * Reflects an item added in QuickAddActivity immediately into the ViewModel state
     * and triggers a background sync.
     */
    fun onQuickAddCompleted(entityId: String) {
        todoRepository.getCachedTodoItems(entityId)?.let { cached ->
            _uiState.update { state ->
                state.copy(itemsByList = state.itemsByList + (entityId to cached))
            }
        }
        TodoSyncManager.triggerSync(appContext, scopeAccountId)
    }

    fun toggleItem(item: TodoItem) {
        val entityId = _uiState.value.selectedListId ?: return
        viewModelScope.launch {
            todoRepository.updateItemStatus(entityId, item.uid, !item.isCompleted).fold(
                onSuccess = { loadItems(entityId, force = true) },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
            )
        }
    }

    fun removeItem(item: TodoItem) {
        val entityId = _uiState.value.selectedListId ?: return
        viewModelScope.launch {
            todoRepository.removeItem(entityId, item.uid).fold(
                onSuccess = { loadItems(entityId, force = true) },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
            )
        }
    }

    fun createList(name: String) {
        viewModelScope.launch {
            val newList = todoRepository.createList(name)
            loadTodoLists()
            selectList(newList.entityId)
        }
    }

    fun deleteList(entityId: String) {
        viewModelScope.launch {
            todoRepository.deleteList(entityId)
            _uiState.value = _uiState.value.copy(selectedListId = null)
            loadTodoLists()
        }
    }

    fun logout() {
        authRepository.logout()
        _uiState.value = _uiState.value.copy(isLoggedOut = true)
    }

    fun switchServer(accountId: String) {
        if (accountId == tokenManager.activeAccountId) return
        tokenManager.activeAccountId = accountId
        OverdueNotificationScheduler.reschedule(appContext)
        _uiState.value = _uiState.value.copy(serverChanged = true)
    }

    private fun handleUnauthorized(isLoadingLists: Boolean = _uiState.value.isLoadingLists) {
        // Another server stays signed in: drop only the rejected one and reload.
        if (!tokenManager.isDemoMode && !authRepository.removeActiveAccount()) {
            _uiState.value = _uiState.value.copy(serverChanged = true)
            return
        }
        authRepository.logout()
        _uiState.value = _uiState.value.copy(
            isLoadingLists = isLoadingLists,
            todoLists = emptyList(),
            selectedListId = null,
            itemsByList = emptyMap(),
            loadingListIds = emptySet(),
            lastLoadedAt = emptyMap(),
            error = null,
            isLoggedOut = true,
            isLocalMode = false
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
