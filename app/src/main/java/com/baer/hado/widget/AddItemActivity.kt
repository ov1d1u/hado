package com.baer.hado.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.annotation.StringRes
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.zIndex
import com.baer.hado.ui.theme.TaskSurfaces
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baer.hado.R
import com.baer.hado.data.local.AddItemPosition
import com.baer.hado.data.local.AppPreferencesManager
import com.baer.hado.data.local.LocalTodoStore
import com.baer.hado.data.local.TodoCacheManager
import com.baer.hado.data.local.TokenManager
import com.baer.hado.data.model.HaState
import com.baer.hado.data.model.TodoItem
import com.baer.hado.data.model.TodoItemStatus
import com.baer.hado.data.model.TodoListFeature
import com.baer.hado.data.sync.TodoSyncManager
import com.baer.hado.ui.theme.HadoTheme
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddItemActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val entityId = intent.getStringExtra("entity_id")
        val listName = intent.getStringExtra("list_name") ?: "To-Do"
        val supportedFeatures = intent.getIntExtra("supported_features", 0)
        val detailItemUid = intent.getStringExtra("detail_item_uid")
        val launchedFromApp = intent.getBooleanExtra("launched_from_app", false)
        val autoFocusInput = intent.getBooleanExtra("auto_focus_input", false)
        val targetAppWidgetId = intent.getIntExtra("app_widget_id", AppWidgetManager.INVALID_APPWIDGET_ID)
            .takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID }
        val tokenManager = TokenManager(this)
        val isLocalMode = tokenManager.isDemoMode
        val accountId = targetAppWidgetId?.let { WidgetSettingsManager.accountIdFor(this, it, tokenManager) }

        // A widget whose server was removed must not fall back to another server.
        if (entityId.isNullOrBlank() || (targetAppWidgetId != null && !isLocalMode && accountId == null)) {
            finish()
            return
        }

        setContent {
            HadoTheme {
                TodoListEditor(
                    context = this,
                    entityId = entityId,
                    listName = listName,
                    supportedFeatures = supportedFeatures,
                    httpClient = WidgetHttpClient(this, accountId),
                    isLocalMode = isLocalMode,
                    showOpenAppAction = !launchedFromApp,
                    onBack = { finish() },
                    onChanged = {
                        targetAppWidgetId?.let { TodoWidgetWorker.enqueueOneTime(this, it) }
                            ?: TodoWidgetWorker.enqueueOneTime(this)
                    },
                    autoDetailItemUid = detailItemUid,
                    widgetAppWidgetId = targetAppWidgetId,
                    autoFocusInput = autoFocusInput
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TodoListEditor(
    context: Context,
    entityId: String,
    listName: String,
    supportedFeatures: Int,
    httpClient: WidgetHttpClient,
    isLocalMode: Boolean = false,
    showOpenAppAction: Boolean = true,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    autoDetailItemUid: String? = null,
    modifier: Modifier = Modifier,
    showTopBar: Boolean = true,
    showListHeader: Boolean = true,
    contentBottomPadding: Dp = 80.dp,
    initialItems: List<TodoItem> = emptyList(),
    refreshOnLaunch: Boolean = true,
    onItemsChanged: (List<TodoItem>) -> Unit = {},
    /** Inline "+ List item" row above the active items; off where a floating add button is used instead. */
    showAddRow: Boolean = true,
    widgetAppWidgetId: Int? = null,
    autoFocusInput: Boolean = false,
    moveTargets: List<HaState> = emptyList(),
    onItemMoved: (targetEntityId: String) -> Unit = {},
    /** Shows a "Delete list" entry in the card header menu when set. */
    onDeleteList: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val gson = remember { Gson() }
    val localStore = remember { if (isLocalMode) LocalTodoStore(context) else null }
    val supportsDescription = TodoListFeature.hasFeature(supportedFeatures, TodoListFeature.SET_DESCRIPTION_ON_ITEM)
    val supportsDueDate = TodoListFeature.hasFeature(supportedFeatures, TodoListFeature.SET_DUE_DATE_ON_ITEM)
    val supportsDueDatetime = TodoListFeature.hasFeature(supportedFeatures, TodoListFeature.SET_DUE_DATETIME_ON_ITEM)
    // Load cached items instantly, then refresh from HA
    // Cache and icon keys include the server so equal entity ids on two servers stay apart.
    val storageKey = TokenManager.scopedKey(httpClient.accountId, entityId)
    val cachedItems = remember(entityId) {
        val cached = TodoCacheManager.getEffectiveItems(context, httpClient.accountId, entityId)
        Log.d("HAdo", "Cache for $entityId: ${cached?.size ?: "null"} items")
        cached
    }
    var items by remember(entityId) { mutableStateOf(initialItems.ifEmpty { cachedItems ?: emptyList() }) }
    var isLoading by remember(entityId) {
        mutableStateOf(refreshOnLaunch && cachedItems == null && initialItems.isEmpty())
    }
    var newItemText by remember(entityId) { mutableStateOf("") }
    val addFocusRequester = remember { FocusRequester() }
    var refocusTrigger by remember(entityId) { mutableIntStateOf(0) }
    var completedExpanded by remember(entityId) { mutableStateOf(!AppPreferencesManager.loadHideCompleted(context)) }
    var pendingDeletes by remember(entityId) { mutableStateOf(emptySet<String>()) }
    var newlyAddedUids by remember(entityId) { mutableStateOf(setOf<String>()) }
    var draggedItemUid by remember(entityId) { mutableStateOf<String?>(null) }
    var detailItem by remember(entityId) { mutableStateOf<TodoItem?>(null) }
    var moveCandidate by remember(entityId) { mutableStateOf<TodoItem?>(null) }
    val supportsDelete = TodoListFeature.hasFeature(supportedFeatures, TodoListFeature.DELETE_TODO_ITEM)
    var dragOffsetY by remember(entityId) { mutableFloatStateOf(0f) }
    var isAddInputFocused by remember(entityId) { mutableStateOf(false) }
    val checkboxOnly = remember { AppPreferencesManager.loadCheckboxOnly(context) }
    val itemHeightPx = with(LocalDensity.current) { 48.dp.toPx() }
    val haptic = LocalHapticFeedback.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val resolvedIcon = remember(entityId) {
        ListIconManager.resolveIcon(context, storageKey)
    }
    // Always-current items ref for gesture callbacks (avoids stale closures)
    val currentItemsState by rememberUpdatedState(items)

    // The list header item only exists when there is no top bar; the add row follows it.
    fun addRowIndex(): Int = if (showListHeader && !showTopBar) 1 else 0

    // Re-focus input field after adding an item (deferred to next frame)
    LaunchedEffect(refocusTrigger) {
        if (refocusTrigger > 0 && showAddRow) {
            listState.animateScrollToItem(addRowIndex())
            addFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    LaunchedEffect(entityId, initialItems) {
        if (initialItems.isNotEmpty() || cachedItems == null) {
            items = initialItems.ifEmpty {
                TodoCacheManager.getEffectiveItems(context, httpClient.accountId, entityId).orEmpty()
            }
            isLoading = false
        }
    }

    // Observe cache updates (e.g., localUid -> remoteUid replacement or external QuickAdd)
    LaunchedEffect(entityId, httpClient.accountId) {
        TodoCacheManager.updates.collect { event ->
            val targetAccount = if (isLocalMode) null else (httpClient.accountId ?: TokenManager(context).activeAccountId)
            if (event.accountId == targetAccount && (event.entityId == null || event.entityId == entityId)) {
                val updated = TodoCacheManager.getEffectiveItems(context, targetAccount, entityId)
                if (updated != null && draggedItemUid == null) {
                    items = updated
                }
            }
        }
    }

    LaunchedEffect(entityId, items) {
        onItemsChanged(items)
    }

    LaunchedEffect(isAddInputFocused, imeVisible, items.count { !it.isCompleted }, showListHeader) {
        if (isAddInputFocused) {
            listState.animateScrollToItem(addRowIndex())
        }
    }

    BackHandler(enabled = isAddInputFocused && !imeVisible) {
        clearAddInputFocus(
            focusManager = focusManager,
            keyboardController = keyboardController,
            onFocusChanged = { isAddInputFocused = false }
        )
    }

    // Fetch items on launch (background refresh)
    LaunchedEffect(entityId) {
        if (!refreshOnLaunch) {
            isLoading = false
            return@LaunchedEffect
        }

        if (isLocalMode) {
            val localItems = localStore?.getItems(entityId) ?: emptyList()
            items = localItems
            TodoCacheManager.saveItems(context, null, entityId, localItems, notify = false)
            isLoading = false
        } else {
            withContext(Dispatchers.IO) {
                try {
                    TodoSyncManager.drainQueue(context, httpClient.accountId)
                    val payload = gson.toJson(mapOf("entity_id" to entityId))
                    val response = httpClient.post(
                        "api/services/todo/get_items?return_response", payload
                    )
                    response?.use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string()
                            if (body != null) {
                                val freshItems = parseItemsFromResponse(gson, body, entityId)
                                val effective = TodoCacheManager.mergeRemoteItemsAndSave(
                                    context = context,
                                    accountId = httpClient.accountId,
                                    entityId = entityId,
                                    remoteItems = freshItems,
                                    notify = false
                                )
                                withContext(Dispatchers.Main) {
                                    items = effective
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("HAdo", "Failed to fetch items", e)
                }
                isLoading = false
            }
        }
    }

    // Auto-focus add input on first load when requested (e.g. widget list header tap)
    LaunchedEffect(Unit) {
        if (autoFocusInput && showAddRow) {
            // Delay to ensure window has gained input focus and layout is complete
            delay(350)
            refocusTrigger++
        }
    }

    // Auto-open detail dialog when launched from widget item tap
    var autoDetailHandled by remember(entityId, autoDetailItemUid) { mutableStateOf(false) }
    LaunchedEffect(items, autoDetailHandled) {
        if (autoDetailItemUid != null && !autoDetailHandled && items.isNotEmpty()) {
            val target = items.find { it.uid == autoDetailItemUid }
            if (target != null) {
                detailItem = target
                autoDetailHandled = true
            }
        }
    }

    fun showError(@StringRes messageResId: Int) {
        scope.launch(Dispatchers.Main) {
            snackbarHostState.showSnackbar(context.getString(messageResId))
        }
    }

    fun addItem(text: String) {
        Log.d("HAdo", "addItem called with text='$text'")
        if (text.isBlank()) return
        val trimmed = text.trim()
        newItemText = ""
        val addPosition = AppPreferencesManager.loadAddItemPosition(context)
        val prepend = addPosition == AddItemPosition.TOP

        // Trigger re-focus on next frame for rapid multi-add
        refocusTrigger++

        val addedItem = TodoSyncManager.addItem(
            context = context,
            accountId = httpClient.accountId,
            entityId = entityId,
            summary = trimmed,
            position = addPosition,
            isLocalMode = isLocalMode,
            preferredAppWidgetId = widgetAppWidgetId
        )
        newlyAddedUids += addedItem.uid
        if (items.none { it.uid == addedItem.uid }) {
            items = if (prepend) listOf(addedItem) + items else items + listOf(addedItem)
        }
    }

    fun toggleItem(item: TodoItem) {
        val completed = !item.isCompleted
        // Optimistic update immediately
        items = items.map {
            if (it.uid == item.uid) it.copy(
                status = if (completed) TodoItemStatus.COMPLETED else TodoItemStatus.NEEDS_ACTION
            ) else it
        }
        TodoSyncManager.toggleItem(
            context = context,
            accountId = httpClient.accountId,
            entityId = entityId,
            itemUid = item.uid,
            completed = completed,
            isLocalMode = isLocalMode,
            preferredAppWidgetId = widgetAppWidgetId
        )
    }

    fun deleteItem(item: TodoItem) {
        pendingDeletes = pendingDeletes + item.uid
        scope.launch {
            delay(300) // match exit animation duration
            items = items.filter { it.uid != item.uid }
            pendingDeletes = pendingDeletes - item.uid
            TodoSyncManager.deleteItem(
                context = context,
                accountId = httpClient.accountId,
                entityId = entityId,
                itemUid = item.uid,
                isLocalMode = isLocalMode,
                preferredAppWidgetId = widgetAppWidgetId
            )
        }
    }

    fun showMessage(message: String) {
        scope.launch(Dispatchers.Main) { snackbarHostState.showSnackbar(message) }
    }

    // Moves an item to another list locally right away and queues the remote add + remove.
    fun moveItem(item: TodoItem, target: HaState) {
        val targetId = target.entityId
        val targetName = target.attributes.friendlyName ?: targetId
        val targetFeatures = target.attributes.supportedFeatures ?: 0
        val current = items.firstOrNull { it.uid == item.uid } ?: item
        val addPosition = AppPreferencesManager.loadAddItemPosition(context)

        pendingDeletes = pendingDeletes + current.uid
        scope.launch {
            delay(300) // match exit animation duration
            if (current.uid in pendingDeletes) {
                items = items.filter { it.uid != current.uid }
                pendingDeletes = pendingDeletes - current.uid
            }
            TodoSyncManager.moveItemToList(
                context = context,
                accountId = httpClient.accountId,
                fromEntityId = entityId,
                toEntityId = targetId,
                targetFeatures = targetFeatures,
                item = current,
                position = addPosition,
                isLocalMode = isLocalMode,
                preferredAppWidgetId = widgetAppWidgetId
            )
            showMessage(context.getString(R.string.move_done, targetName))
            onItemMoved(targetId)
        }
    }

    val pullToRefreshState = rememberPullToRefreshState()
    LaunchedEffect(pullToRefreshState.isRefreshing) {
        if (!pullToRefreshState.isRefreshing) return@LaunchedEffect
        if (isLocalMode) {
            val localItems = localStore?.getItems(entityId) ?: emptyList()
            items = localItems
            TodoCacheManager.saveItems(context, null, entityId, localItems, notify = false)
        } else {
            withContext(Dispatchers.IO) {
                try {
                    TodoSyncManager.drainQueue(context, httpClient.accountId)
                    val payload = gson.toJson(mapOf("entity_id" to entityId))
                    val response = httpClient.post(
                        "api/services/todo/get_items?return_response", payload
                    )
                    response?.use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string()
                            if (body != null) {
                                val freshItems = parseItemsFromResponse(gson, body, entityId)
                                val effective = TodoCacheManager.mergeRemoteItemsAndSave(
                                    context = context,
                                    accountId = httpClient.accountId,
                                    entityId = entityId,
                                    remoteItems = freshItems,
                                    notify = false
                                )
                                withContext(Dispatchers.Main) { items = effective }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("HAdo", "Pull refresh failed", e)
                }
            }
        }
        pullToRefreshState.endRefresh()
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState, modifier = Modifier.imePadding()) },
        topBar = {
            if (showTopBar) {
                TopAppBar(
                    title = {
                        ListTitle(
                            resolvedIcon = resolvedIcon,
                            listName = listName,
                            iconSize = 28.dp,
                            textStyle = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back)
                            )
                        }
                    },
                    actions = {
                        if (showOpenAppAction) {
                            IconButton(onClick = {
                                val intent = context.packageManager
                                    .getLaunchIntentForPackage(context.packageName)
                                if (intent != null) {
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                }
                            }) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = stringResource(R.string.cd_open_app),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = TaskSurfaces.page,
                        scrolledContainerColor = TaskSurfaces.page,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        },
        containerColor = TaskSurfaces.page
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (showTopBar) Modifier.padding(padding) else Modifier)
                .nestedScroll(pullToRefreshState.nestedScrollConnection)
        ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
                contentPadding = PaddingValues(top = 8.dp, bottom = contentBottomPadding)
            ) {
                // Top of the tasks card: the list name, or a thin cap when the top bar already shows it.
                item(key = "card-top") {
                    if (showListHeader && !showTopBar) {
                        CardHeader(
                            resolvedIcon = resolvedIcon,
                            listName = listName,
                            onDeleteList = onDeleteList
                        )
                    } else {
                        Spacer(
                            modifier = Modifier
                                .cardSection(TaskSurfaces.cardTopShape)
                                .height(8.dp)
                        )
                    }
                }

                // Add item row (above uncompleted items)
                if (showAddRow) {
                    item(key = "add-item-row") {
                        Box(modifier = Modifier.cardSection()) {
                            AddItemRow(
                                text = newItemText,
                                onTextChange = { newItemText = it },
                                onAdd = { addItem(newItemText) },
                                focusRequester = addFocusRequester,
                                onFocusChanged = { isFocused ->
                                    isAddInputFocused = isFocused
                                }
                            )
                        }
                    }
                }

                // Uncompleted items with drag handles
                val uncompleted = items.filter { !it.isCompleted }
                if (uncompleted.isEmpty() && !showAddRow && !isLoading) {
                    item(key = "empty-tasks") {
                        Text(
                            text = stringResource(R.string.home_no_items),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .cardSection()
                                .padding(horizontal = 20.dp, vertical = 24.dp)
                        )
                    }
                }
                itemsIndexed(uncompleted, key = { _, item -> item.uid }) { _, item ->
                    val isDragging = draggedItemUid == item.uid

                    Box(
                        modifier = Modifier
                            .then(if (draggedItemUid != null) Modifier else Modifier.animateItemPlacement())
                            // The dragged row floats over its neighbours' card background.
                            .zIndex(if (isDragging) 1f else 0f)
                            .cardSection()
                    ) {
                    AnimatedVisibility(
                        visible = item.uid !in pendingDeletes,
                        enter = if (item.uid in newlyAddedUids) fadeIn(tween(300)) + expandVertically(
                            expandFrom = Alignment.Top,
                            animationSpec = tween(300)
                        ) else EnterTransition.None,
                        exit = fadeOut(tween(300)) + shrinkVertically(
                            shrinkTowards = Alignment.Top,
                            animationSpec = tween(300)
                        )
                    ) {
                        TodoItemRow(
                            item = item,
                            onToggle = { toggleItem(item) },
                            onDelete = { deleteItem(item) },
                            onOpenDetails = { detailItem = item },
                            showDragHandle = true,
                            isDragging = isDragging,
                            dragOffsetY = if (isDragging) dragOffsetY else 0f,
                            checkboxOnly = checkboxOnly,
                            onDragStart = {
                                draggedItemUid = item.uid
                                dragOffsetY = 0f
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDrag = { deltaY ->
                                dragOffsetY += deltaY
                                val uid = draggedItemUid ?: return@TodoItemRow
                                // Dynamically find current position (avoids stale closure)
                                val liveUncompleted = currentItemsState.filter { !it.isCompleted }
                                val currentIndex = liveUncompleted.indexOfFirst { it.uid == uid }
                                if (currentIndex < 0) return@TodoItemRow

                                val targetIndex = when {
                                    dragOffsetY > 0 && currentIndex < liveUncompleted.lastIndex -> currentIndex + 1
                                    dragOffsetY < 0 && currentIndex > 0 -> currentIndex - 1
                                    else -> return@TodoItemRow
                                }
                                // Rows vary in height (due date, description), so the swap
                                // threshold and offset correction use measured heights.
                                val neighbourHeight = rowHeightPx(
                                    listState,
                                    liveUncompleted[targetIndex].uid,
                                    itemHeightPx
                                )
                                if (kotlin.math.abs(dragOffsetY) <= neighbourHeight / 2) return@TodoItemRow

                                val mutableItems = currentItemsState.toMutableList()
                                val draggedItem = liveUncompleted[currentIndex]
                                val targetItem = liveUncompleted[targetIndex]
                                val fromGlobal = mutableItems.indexOf(draggedItem)
                                val toGlobal = mutableItems.indexOf(targetItem)
                                if (fromGlobal >= 0 && toGlobal >= 0) {
                                    mutableItems.removeAt(fromGlobal)
                                    mutableItems.add(toGlobal, draggedItem)
                                    items = mutableItems
                                    // Keep the row under the finger after the swap
                                    dragOffsetY -= (targetIndex - currentIndex) * neighbourHeight
                                }
                            },
                            onDragEnd = {
                                val finishedUid = draggedItemUid
                                draggedItemUid = null
                                dragOffsetY = 0f
                                if (finishedUid != null) {
                                    val finalUncompleted = currentItemsState.filter { !it.isCompleted }
                                    val finalIndex = finalUncompleted.indexOfFirst { it.uid == finishedUid }
                                    if (finalIndex >= 0) {
                                        val prevUid = if (finalIndex > 0) finalUncompleted[finalIndex - 1].uid else null
                                        TodoSyncManager.reorderItem(
                                            context = context,
                                            accountId = httpClient.accountId,
                                            entityId = entityId,
                                            reorderedItems = currentItemsState,
                                            movedUid = finishedUid,
                                            previousUid = prevUid,
                                            isLocalMode = isLocalMode,
                                            preferredAppWidgetId = widgetAppWidgetId
                                        )
                                    }
                                }
                            }
                        )
                    }
                    }
                }

                item(key = "card-bottom") {
                    Spacer(
                        modifier = Modifier
                            .cardSection(TaskSurfaces.cardBottomShape)
                            .height(12.dp)
                    )
                }

                // Completed items live in their own card under the tasks card
                val completed = items.filter { it.isCompleted }
                if (completed.isNotEmpty()) {
                    item(key = "completed-header") {
                        CompletedSectionHeader(
                            count = completed.size,
                            expanded = completedExpanded,
                            onToggle = { completedExpanded = !completedExpanded },
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .cardSection(
                                    if (completedExpanded) TaskSurfaces.cardTopShape else TaskSurfaces.cardShape
                                )
                        )
                    }
                    if (completedExpanded) {
                        items(completed, key = { it.uid }) { item ->
                            Box(
                                modifier = Modifier
                                    .animateItemPlacement()
                                    .cardSection()
                            ) {
                                AnimatedVisibility(
                                    visible = item.uid !in pendingDeletes,
                                    enter = if (item.uid in newlyAddedUids) fadeIn(tween(300)) + expandVertically(
                                        expandFrom = Alignment.Top,
                                        animationSpec = tween(300)
                                    ) else EnterTransition.None,
                                    exit = fadeOut(tween(300)) + shrinkVertically(
                                        shrinkTowards = Alignment.Top,
                                        animationSpec = tween(300)
                                    )
                                ) {
                                    TodoItemRow(
                                        item = item,
                                        onToggle = { toggleItem(item) },
                                        onDelete = { deleteItem(item) },
                                        onOpenDetails = { detailItem = item },
                                        showDragHandle = false,
                                        checkboxOnly = checkboxOnly
                                    )
                                }
                            }
                        }
                        item(key = "completed-bottom") {
                            Spacer(
                                modifier = Modifier
                                    .cardSection(TaskSurfaces.cardBottomShape)
                                    .height(8.dp)
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            PullToRefreshContainer(state = pullToRefreshState)
        }
        }  // end Box

        // Item detail dialog (long-press)
        detailItem?.let { item ->
        ItemDetailDialog(
            context = context,
            item = item,
            supportsDescription = supportsDescription,
            supportsDueDate = supportsDueDate,
            supportsDueDatetime = supportsDueDatetime,
            onDismiss = { detailItem = null },
            onMoveToList = if (moveTargets.isNotEmpty() && supportsDelete) {
                {
                    detailItem = null
                    moveCandidate = item
                }
            } else null,
            onSave = { updatedItem ->
                // Optimistic UI update
                items = items.map { if (it.uid == item.uid) updatedItem else it }
                detailItem = null
                TodoSyncManager.updateItemDetails(
                    context = context,
                    accountId = httpClient.accountId,
                    entityId = entityId,
                    original = item,
                    updated = updatedItem,
                    isLocalMode = isLocalMode,
                    preferredAppWidgetId = widgetAppWidgetId
                )
            }
        )
    }

    moveCandidate?.let { item ->
        MoveToListDialog(
            context = context,
            accountId = httpClient.accountId,
            item = items.firstOrNull { it.uid == item.uid } ?: item,
            targets = moveTargets,
            onDismiss = { moveCandidate = null },
            onPick = { target ->
                moveCandidate = null
                moveItem(item, target)
            }
        )
    }
}

@Composable
private fun MoveToListDialog(
    context: Context,
    accountId: String?,
    item: TodoItem,
    targets: List<HaState>,
    onDismiss: () -> Unit,
    onPick: (HaState) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_move_to_list_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                targets.forEach { target ->
                    val features = target.attributes.supportedFeatures ?: 0
                    val enabled = TodoListFeature.hasFeature(features, TodoListFeature.CREATE_TODO_ITEM)
                    val losses = remember(item, features) { buildMovePayload(item, features).losses }
                    val icon = remember(target.entityId) { ListIconManager.resolveIcon(context, TokenManager.scopedKey(accountId, target.entityId)) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable(enabled = enabled) { onPick(target) }
                            .alpha(if (enabled) 1f else 0.38f)
                            .defaultMinSize(minHeight = 48.dp)
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ListIconPreview(
                            resolvedIcon = icon,
                            size = 28.dp,
                            modifier = Modifier.padding(end = 12.dp),
                            emojiColor = MaterialTheme.colorScheme.onSurface,
                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                            fallbackEmoji = "📋"
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = target.attributes.friendlyName ?: target.entityId,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (enabled && losses.isNotEmpty()) {
                                Text(
                                    text = losses.joinToString(" · ") { context.getString(it) },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/** Measured height of the laid-out row with [uid], or [fallbackPx] when it is off-screen. */
private fun rowHeightPx(listState: LazyListState, uid: String, fallbackPx: Float): Float {
    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == uid }
    return info?.size?.toFloat()?.takeIf { it > 0f } ?: fallbackPx
}

@Composable
private fun ListTitle(
    resolvedIcon: ListIconManager.ResolvedIcon?,
    listName: String,
    iconSize: Dp,
    textStyle: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (resolvedIcon != null) {
            ListIconPreview(
                resolvedIcon = resolvedIcon,
                size = iconSize,
                modifier = Modifier.padding(end = 10.dp),
                emojiColor = MaterialTheme.colorScheme.onSurface,
                backgroundColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
        Text(
            text = listName,
            style = textStyle,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TodoItemRow(
    item: TodoItem,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onOpenDetails: () -> Unit = {},
    showDragHandle: Boolean,
    isDragging: Boolean = false,
    dragOffsetY: Float = 0f,
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
    checkboxOnly: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                if (isDragging) {
                    translationY = dragOffsetY
                    shadowElevation = 8f
                    shape = RoundedCornerShape(12.dp)
                    clip = true
                    scaleX = 1.02f
                    scaleY = 1.02f
                }
            }
            .background(TaskSurfaces.card)
            .combinedClickable(
                onClick = { if (checkboxOnly) onOpenDetails() else onToggle() },
                onLongClick = { onOpenDetails() }
            )
            .height(IntrinsicSize.Min)
            .defaultMinSize(minHeight = 52.dp)
            .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Drag handle (Keep: 48dp touch target, only on uncompleted)
        if (showDragHandle) {
            Icon(
                Icons.Default.DragIndicator,
                contentDescription = stringResource(R.string.cd_drag_reorder),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(48.dp)
                    .padding(12.dp)
                    .pointerInput(item.uid) {
                        detectDragGestures(
                            onDragStart = { onDragStart() },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                            onDrag = { change, offset ->
                                change.consume()
                                onDrag(offset.y)
                            }
                        )
                    }
            )
        }

        RoundCheckbox(checked = item.isCompleted, onToggle = onToggle)

        // Item text with optional description preview + due date
        Spacer(modifier = Modifier.width(4.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp)
        ) {
            Text(
                text = item.summary,
                style = MaterialTheme.typography.bodyLarge,
                color = if (item.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (item.isCompleted) TextDecoration.LineThrough else null,
            )
            // Description preview (first line only)
            if (!item.description.isNullOrBlank()) {
                Text(
                    text = item.description.lines().first(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (item.due != null) {
                val dueText = formatDueDisplay(androidx.compose.ui.platform.LocalContext.current, item)
                val isToday = (item.dueDateTime?.toLocalDate() ?: item.dueDate) == java.time.LocalDate.now()
                Text(
                    text = dueText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        item.isCompleted -> MaterialTheme.colorScheme.onSurfaceVariant
                        item.isOverdue -> MaterialTheme.colorScheme.error
                        isToday -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        // Delete button — X icon
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.cd_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** Google Tasks style toggle: an empty ring, or a filled circle with a check when done. */
@Composable
private fun RoundCheckbox(checked: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = { onToggle() }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
            )
        }
    }
}

@Composable
private fun CompletedSectionHeader(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clickable(onClick = onToggle)
            .defaultMinSize(minHeight = 56.dp)
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.completed_count, count),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp
            else Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) stringResource(R.string.cd_collapse) else stringResource(R.string.cd_expand),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp).padding(12.dp)
        )
    }
}

/** One horizontal slice of a Google Tasks style card; stacked slices read as one card. */
private fun Modifier.cardSection(shape: Shape? = null): Modifier = composed {
    val color = TaskSurfaces.card
    this
        .fillMaxWidth()
        .padding(horizontal = TaskSurfaces.cardHorizontalMargin)
        .then(if (shape != null) Modifier.clip(shape) else Modifier)
        .background(color)
}

@Composable
private fun CardHeader(
    resolvedIcon: ListIconManager.ResolvedIcon?,
    listName: String,
    onDeleteList: (() -> Unit)?
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .cardSection(TaskSurfaces.cardTopShape)
            .defaultMinSize(minHeight = 60.dp)
            .padding(start = 20.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ListTitle(
            resolvedIcon = resolvedIcon,
            listName = listName,
            iconSize = 26.dp,
            textStyle = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f)
        )
        if (onDeleteList != null) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.cd_more_options),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.cd_delete_list)) },
                        onClick = {
                            menuOpen = false
                            onDeleteList()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AddItemRow(
    text: String,
    onTextChange: (String) -> Unit,
    onAdd: () -> Unit,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 46.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = stringResource(R.string.cd_add_item),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        TextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = {
                Text(
                    stringResource(R.string.placeholder_list_item),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { focusState ->
                    onFocusChanged(focusState.isFocused)
                },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    Log.d("HAdo", "Keyboard send, text='$text'")
                    onAdd()
                }
            )
        )
        if (text.isNotBlank()) {
            IconButton(onClick = {
                Log.d("HAdo", "Send button clicked, text='$text'")
                onAdd()
            }) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.cd_add_item),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

private fun clearAddInputFocus(
    focusManager: FocusManager,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    onFocusChanged: () -> Unit
) {
    keyboardController?.hide()
    focusManager.clearFocus(force = true)
    onFocusChanged()
}

/** The newly added item plus whether HA already placed it first among active items. */
internal data class AddedItem(val item: TodoItem, val alreadyFirstActive: Boolean)

/**
 * Resolves the newly added item by finding the new UID after an add_item call,
 * and reports whether it is already the first active item (so a redundant move
 * to the top can be skipped).
 */
internal fun resolveAddedItem(
    httpClient: WidgetHttpClient,
    gson: Gson,
    entityId: String,
    knownItemUids: Set<String>,
    expectedSummary: String
): AddedItem? {
    val payload = gson.toJson(mapOf("entity_id" to entityId))
    val response = httpClient.post("api/services/todo/get_items?return_response", payload) ?: return null

    var resolved: AddedItem? = null

    response.use { resp ->
        if (!resp.isSuccessful) {
            Log.w("HAdo", "Failed to fetch items after add: ${resp.code}")
            return@use
        }

        val body = resp.body?.string() ?: return@use
        val refreshedItems = parseItemsFromResponse(gson, body, entityId)
        val item = refreshedItems.firstOrNull {
            it.uid !in knownItemUids && it.summary == expectedSummary
        } ?: refreshedItems.firstOrNull { it.uid !in knownItemUids } ?: return@use
        val firstActiveUid = refreshedItems.firstOrNull { !it.isCompleted }?.uid
        resolved = AddedItem(item, alreadyFirstActive = firstActiveUid == item.uid)
    }

    return resolved
}

/** Items of [entityId] from HA, or null when the request failed. */
internal fun fetchListItems(httpClient: WidgetHttpClient, gson: Gson, entityId: String): List<TodoItem>? {
    val payload = gson.toJson(mapOf("entity_id" to entityId))
    return httpClient.post("api/services/todo/get_items?return_response", payload)?.use { resp ->
        val body = resp.body?.string()
        if (resp.isSuccessful && body != null) parseItemsFromResponse(gson, body, entityId) else null
    }
}

/** An item's fields reduced to what a target list supports, plus the captions for what is dropped. */
internal data class MovePlan(
    val description: String?,
    /** Mapped due in the source format: `YYYY-MM-DD` when [dueIsDatetime] is false. */
    val due: String?,
    val dueIsDatetime: Boolean,
    val markCompleted: Boolean,
    /** String resource ids of the loss captions, in field order. */
    val losses: List<Int>
) {
    /** Extra `todo.add_item` fields; HA rejects the whole call if one is unsupported by the target. */
    fun haFields(): Map<String, String> = buildMap {
        description?.let { put("description", it) }
        due?.let { if (dueIsDatetime) put("due_datetime", it.replace("T", " ")) else put("due_date", it) }
    }
}

internal fun buildMovePayload(item: TodoItem, targetFeatures: Int): MovePlan {
    fun has(feature: Int) = TodoListFeature.hasFeature(targetFeatures, feature)
    val losses = mutableListOf<Int>()

    val description = item.description?.takeIf { it.isNotBlank() }
    val keptDescription = if (description != null && !has(TodoListFeature.SET_DESCRIPTION_ON_ITEM)) {
        losses += R.string.move_loss_description
        null
    } else description

    var due: String? = null
    var dueIsDatetime = false
    val sourceDue = item.due?.takeIf { it.isNotBlank() }
    if (sourceDue != null) {
        val hasTime = sourceDue.contains("T") || (sourceDue.contains(" ") && sourceDue.length > 10)
        val datePart = sourceDue.substringBefore("T").substringBefore(" ")
        when {
            hasTime && has(TodoListFeature.SET_DUE_DATETIME_ON_ITEM) -> { due = sourceDue; dueIsDatetime = true }
            hasTime && has(TodoListFeature.SET_DUE_DATE_ON_ITEM) -> { due = datePart; losses += R.string.move_loss_time }
            !hasTime && has(TodoListFeature.SET_DUE_DATE_ON_ITEM) -> due = datePart
            else -> losses += R.string.move_loss_due
        }
    }

    val markCompleted = item.isCompleted && has(TodoListFeature.UPDATE_TODO_ITEM)
    if (item.isCompleted && !markCompleted) losses += R.string.move_loss_status

    return MovePlan(keptDescription, due, dueIsDatetime, markCompleted, losses)
}

private fun parseItemsFromResponse(
    gson: Gson, json: String, entityId: String
): List<TodoItem> {
    return try {
        val type = object : TypeToken<Map<String, Any>>() {}.type
        val root: Map<String, Any> = gson.fromJson(json, type)
        val serviceResponse = root["service_response"] as? Map<*, *> ?: root
        val entityData = serviceResponse[entityId] as? Map<*, *> ?: return emptyList()
        val itemsList = entityData["items"] ?: return emptyList()
        val itemsJson = gson.toJson(itemsList)
        Log.d("HAdo", "parseItemsFromResponse itemsJson=$itemsJson")
        val itemsType = object : TypeToken<List<TodoItem>>() {}.type
        val parsed: List<TodoItem> = gson.fromJson(itemsJson, itemsType)
        parsed.forEach { item ->
            Log.d("HAdo", "  item uid=${item.uid} summary=${item.summary} due=${item.due} desc=${item.description?.take(30)}")
        }
        parsed
    } catch (_: Exception) {
        emptyList()
    }
}

/** Google Tasks style due text: "Today, 12:30 PM", "Wed, Nov 13", "Nov 13, 2027". */
private fun formatDueDisplay(context: Context, item: TodoItem): String {
    val dt = item.dueDateTime
    val date = dt?.toLocalDate() ?: item.dueDate ?: return ""
    val locale = java.util.Locale.getDefault()
    val today = java.time.LocalDate.now()
    fun pattern(skeleton: String) = java.time.format.DateTimeFormatter.ofPattern(
        android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale
    )
    val dayPart = when {
        date == today -> context.getString(R.string.due_today)
        date == today.plusDays(1) -> context.getString(R.string.due_tomorrow)
        date == today.minusDays(1) -> context.getString(R.string.due_yesterday)
        date.year == today.year -> date.format(pattern("EEEMMMd"))
        else -> date.format(pattern("MMMdyyyy"))
    }
    if (dt == null) return dayPart
    val timeSkeleton = if (android.text.format.DateFormat.is24HourFormat(context)) "Hm" else "hm"
    return "$dayPart, ${dt.format(pattern(timeSkeleton))}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ItemDetailDialog(
    context: Context,
    item: TodoItem,
    supportsDescription: Boolean,
    supportsDueDate: Boolean,
    supportsDueDatetime: Boolean,
    onDismiss: () -> Unit,
    onSave: (TodoItem) -> Unit,
    onMoveToList: (() -> Unit)? = null
) {
    var summary by remember { mutableStateOf(item.summary) }
    var description by remember { mutableStateOf(item.description ?: "") }
    var dueString by remember { mutableStateOf(item.due) }
    var isEditingDescription by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    // Markwon instance for markdown rendering
    val markwon = remember {
        io.noties.markwon.Markwon.builder(context)
            .usePlugin(io.noties.markwon.ext.strikethrough.StrikethroughPlugin.create())
            .usePlugin(io.noties.markwon.ext.tables.TablePlugin.create(context))
            .build()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        title = {
            OutlinedTextField(
                value = summary,
                onValueChange = { summary = it },
                label = { Text(stringResource(R.string.label_title_field)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Description section
                if (supportsDescription) {
                    Text(
                        stringResource(R.string.label_description),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    if (isEditingDescription) {
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 100.dp),
                            placeholder = { Text(stringResource(R.string.placeholder_description)) },
                            maxLines = 10
                        )
                        TextButton(onClick = { isEditingDescription = false }) {
                            Text(stringResource(R.string.label_preview))
                        }
                    } else {
                        if (description.isNotBlank()) {
                            // Render markdown via AndroidView
                            val textColor = MaterialTheme.colorScheme.onSurface
                            val textColorArgb = android.graphics.Color.argb(
                                (textColor.alpha * 255).toInt(),
                                (textColor.red * 255).toInt(),
                                (textColor.green * 255).toInt(),
                                (textColor.blue * 255).toInt()
                            )
                            androidx.compose.ui.viewinterop.AndroidView(
                                factory = { ctx ->
                                    android.widget.TextView(ctx).apply {
                                        setTextColor(textColorArgb)
                                        textSize = 14f
                                    }
                                },
                                update = { tv ->
                                    tv.setTextColor(textColorArgb)
                                    markwon.setMarkdown(tv, description)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isEditingDescription = true }
                                    .padding(8.dp)
                            )
                        } else {
                            TextButton(
                                onClick = { isEditingDescription = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.action_add_description))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Due date section
                if (supportsDueDate || supportsDueDatetime) {
                    Text(
                        stringResource(R.string.label_due_date),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val dateDisplay = if (dueString != null) {
                            val parsed = item.copy(due = dueString)
                            formatDueDisplay(androidx.compose.ui.platform.LocalContext.current, parsed).removePrefix("⚠ ").removePrefix("📅 ")
                        } else stringResource(R.string.label_no_due_date)

                        TextButton(onClick = { showDatePicker = true }) {
                            Icon(
                                Icons.Default.CalendarToday,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(dateDisplay)
                        }
                        if (dueString != null) {
                            IconButton(onClick = { dueString = null }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.cd_clear_due_date),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                if (onMoveToList != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(onClick = onMoveToList) {
                        Icon(
                            Icons.AutoMirrored.Filled.DriveFileMove,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.action_move_to_list))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    item.copy(
                        summary = summary.trim(),
                        description = description.ifBlank { null },
                        due = dueString
                    )
                )
            }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )

    // Material3 DatePicker dialog
    // NOTE: DatePicker works in UTC midnight epoch millis, so we must use ZoneOffset.UTC
    // when converting a LocalDate to millis and back — otherwise users in timezones
    // ahead of UTC would see the date shifted back by one day (the original bug).
    if (showDatePicker) {
        val initialMillis = item.dueDate?.let {
            it.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialMillis
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val date = java.time.Instant.ofEpochMilli(millis)
                            .atZone(java.time.ZoneOffset.UTC)
                            .toLocalDate()
                        dueString = date.toString() // YYYY-MM-DD
                        showDatePicker = false
                        if (supportsDueDatetime) {
                            showTimePicker = true
                        }
                    }
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.action_cancel)) }
            },
            colors = DatePickerDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        ) {
            DatePicker(
                state = datePickerState,
                colors = DatePickerDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            )
        }
    }

    // Material3 TimePicker dialog
    if (showTimePicker && supportsDueDatetime) {
        val existingTime = item.dueDateTime
        val timePickerState = rememberTimePickerState(
            initialHour = existingTime?.hour ?: 12,
            initialMinute = existingTime?.minute ?: 0
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text(stringResource(R.string.dialog_set_time)) },
            text = { TimePicker(state = timePickerState) },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            confirmButton = {
                TextButton(onClick = {
                    // Combine selected date with time
                    val datePrefix = dueString?.substringBefore("T") ?: dueString ?: return@TextButton
                    dueString = "${datePrefix}T${String.format("%02d:%02d:00", timePickerState.hour, timePickerState.minute)}"
                    showTimePicker = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    // Keep date-only (no time)
                    dueString = dueString?.substringBefore("T")
                    showTimePicker = false
                }) { Text(stringResource(R.string.action_skip_time)) }
            }
        )
    }
}
