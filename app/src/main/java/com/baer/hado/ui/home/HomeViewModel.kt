package com.baer.hado.ui.home

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baer.hado.data.local.AddItemPosition
import com.baer.hado.data.local.AppPreferencesManager
import com.baer.hado.data.local.TokenManager
import com.baer.hado.data.model.HaState
import com.baer.hado.data.model.TodoItem
import com.baer.hado.data.model.TodoItemStatus
import com.baer.hado.data.repository.AuthRepository
import com.baer.hado.data.repository.TodoRepository
import com.baer.hado.notifications.OverdueNotificationScheduler
import com.baer.hado.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
        /** How long a saved item stays visible while no fetch confirms it. */
        private const val PENDING_ADD_TTL_MS = 20_000L
        private const val PENDING_ADD_RECHECKS = 5
        private const val PENDING_ADD_RECHECK_DELAY_MS = 1_200L
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
    private fun publishLists(all: List<HaState>, shown: List<HaState>, preferredId: String? = null) {
        allTodoLists = all
        val selectedId = (preferredId ?: _uiState.value.selectedListId)
            ?.takeIf { id -> shown.any { it.entityId == id } }
            ?: shown.firstOrNull()?.entityId

        _uiState.value = _uiState.value.copy(
            todoLists = shown,
            selectedListId = selectedId,
            allListsHidden = all.isNotEmpty() && shown.isEmpty(),
            isLoadingLists = false
        )

        selectedId?.let { loadItems(it) }
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Every list of the active server, before order and the visibility filter. */
    private var allTodoLists: List<HaState> = emptyList()

    /** An item saved in the quick add card that no fetch has confirmed yet. */
    private data class PendingAdd(
        val item: TodoItem,
        val atTop: Boolean,
        val addedAt: Long = System.currentTimeMillis()
    )

    private val pendingAdds = mutableMapOf<String, MutableList<PendingAdd>>()
    private var reconcileJob: Job? = null

    private var loadItemsJob: Job? = null
    private var loadingEntityId: String? = null
    private var activeLoadToken = 0L

    /** List asked for by a deep link; applied as soon as it is known to exist, then cleared. */
    private var requestedListId: String? = savedStateHandle[Routes.ARG_LIST]

    init {
        _uiState.value = _uiState.value.copy(isLocalMode = tokenManager.isDemoMode)
        refreshServers()
        loadTodoLists()
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
            _uiState.value = _uiState.value.copy(isLoadingLists = true, error = null)
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
        _uiState.value = _uiState.value.copy(selectedListId = entityId, error = null)
        loadItems(entityId)
    }

    fun loadItems(entityId: String? = _uiState.value.selectedListId, force: Boolean = false) {
        val id = entityId ?: return
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
                                itemsByList = state.itemsByList + (id to mergePendingAdds(id, items)),
                                lastLoadedAt = state.lastLoadedAt + (id to System.currentTimeMillis())
                            )
                        }
                    },
                    onFailure = { e ->
                        if (loadToken != activeLoadToken) return@fold
                        if (e.message?.contains("401") == true) {
                            Log.w("HAdo", "401 on loadItems — token refresh failed, redirecting to login")
                            handleUnauthorized()
                        } else {
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
     * Shows an item the quick add card just saved, before the write reaches the server, so the
     * list never looks unchanged. The placeholder is merged into every load of that list until
     * the server reports a matching item (or [PENDING_ADD_TTL_MS] passes).
     * [due] is `YYYY-MM-DD` or `YYYY-MM-DDTHH:mm:ss`.
     */
    fun showPendingItem(entityId: String, summary: String, due: String?) {
        val trimmed = summary.trim()
        if (trimmed.isEmpty()) return

        val pending = PendingAdd(
            item = TodoItem(
                uid = "pending_${System.nanoTime()}",
                summary = trimmed,
                status = TodoItemStatus.NEEDS_ACTION,
                due = due
            ),
            atTop = AppPreferencesManager.loadAddItemPosition(appContext) == AddItemPosition.TOP
        )
        pendingAdds.getOrPut(entityId) { mutableListOf() } += pending

        _uiState.update { state ->
            state.copy(
                itemsByList = state.itemsByList + (entityId to mergePendingAdds(entityId, state.itemsFor(entityId)))
            )
        }
        // The write runs outside this screen, so poll briefly for the real item: it carries the
        // uid that toggling and editing need, which the placeholder cannot provide.
        reconcileJob?.cancel()
        reconcileJob = viewModelScope.launch {
            loadItems(entityId, force = true)
            repeat(PENDING_ADD_RECHECKS) {
                delay(PENDING_ADD_RECHECK_DELAY_MS)
                if (pendingAdds[entityId].isNullOrEmpty()) return@launch
                loadItems(entityId, force = true)
            }
        }
    }

    /** Adds the not-yet-confirmed items of [entityId] to [loaded], dropping the ones it covers. */
    private fun mergePendingAdds(entityId: String, loaded: List<TodoItem>): List<TodoItem> {
        val pending = pendingAdds[entityId] ?: return loaded
        val now = System.currentTimeMillis()
        pending.removeAll { candidate ->
            now - candidate.addedAt > PENDING_ADD_TTL_MS ||
                loaded.any { it.summary == candidate.item.summary && !it.isCompleted }
        }
        if (pending.isEmpty()) {
            pendingAdds.remove(entityId)
            return loaded
        }
        val (top, bottom) = pending.partition { it.atTop }
        return top.map { it.item } + loaded + bottom.map { it.item }
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
