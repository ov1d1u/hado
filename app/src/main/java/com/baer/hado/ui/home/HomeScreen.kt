@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.baer.hado.ui.home

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.baer.hado.R
import com.baer.hado.data.local.TokenManager
import com.baer.hado.data.model.TodoItem
import com.baer.hado.ui.theme.AppSpacing
import com.baer.hado.ui.theme.TaskSurfaces
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.HorizontalDivider
import com.baer.hado.widget.ListIconManager
import com.baer.hado.widget.ListIconPreview
import com.baer.hado.widget.QuickAddActivity
import com.baer.hado.widget.TodoListEditor
import com.baer.hado.widget.WidgetHttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

@Composable
fun HomeScreen(
    onLoggedOut: () -> Unit,
    onOpenSettings: () -> Unit,
    onAccountChanged: () -> Unit,
    onAddServer: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val httpClient = remember(context) { WidgetHttpClient(context) }
    val snackbarHostState = remember { SnackbarHostState() }
    var showNewListDialog by remember { mutableStateOf(false) }
    var showDeleteListDialog by remember { mutableStateOf(false) }
    var showSupportDialog by remember { mutableStateOf(false) }
    var showServerMenu by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    val selectedList = uiState.todoLists.find { it.entityId == uiState.selectedListId }
    val selectedListIndex = uiState.todoLists.indexOfFirst { it.entityId == uiState.selectedListId }
        .let { index -> if (index >= 0) index else 0 }
    val selectedListName = selectedList?.attributes?.friendlyName ?: uiState.selectedListId
    val listSelectorState = rememberLazyListState()

    // The quick add card runs as its own activity and writes directly to the local cache.
    val quickAddLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val listId = uiState.selectedListId ?: return@rememberLauncherForActivityResult
        viewModel.onQuickAddCompleted(listId)
    }

    // Settings can change which lists are shown while Home sits on the back stack.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refreshVisibleLists()
        }
    }

    LaunchedEffect(lifecycleOwner, uiState.selectedListId) {
        val selectedId = uiState.selectedListId ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(5_000)
                viewModel.loadItems(selectedId)
            }
        }
    }

    LaunchedEffect(uiState.isLoggedOut) {
        if (uiState.isLoggedOut) onLoggedOut()
    }

    LaunchedEffect(Unit) {
        viewModel.refreshServers()
    }

    LaunchedEffect(uiState.serverChanged) {
        if (uiState.serverChanged) onAccountChanged()
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(selectedListIndex, uiState.todoLists.size) {
        if (uiState.todoLists.size > 1) {
            listSelectorState.animateScrollToItem(selectedListIndex)
        }
    }

    val headerToEditorGap = if (uiState.todoLists.isNotEmpty() && selectedList != null) 0.dp else AppSpacing.cardGap

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (uiState.servers.size > 1) {
                        Box {
                            Row(
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable { showServerMenu = true },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = uiState.servers.firstOrNull { it.first == uiState.activeServerId }?.second
                                        ?: stringResource(R.string.app_name),
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = stringResource(R.string.cd_switch_server)
                                )
                            }
                            DropdownMenu(
                                expanded = showServerMenu,
                                onDismissRequest = { showServerMenu = false }
                            ) {
                                uiState.servers.forEach { (id, name) ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = name,
                                                color = if (id == uiState.activeServerId) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        onClick = {
                                            showServerMenu = false
                                            viewModel.switchServer(id)
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_add_server)) },
                                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                    onClick = {
                                        showServerMenu = false
                                        onAddServer()
                                    }
                                )
                            }
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TaskSurfaces.page,
                    scrolledContainerColor = TaskSurfaces.page,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.cd_settings))
                    }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            if (uiState.isLocalMode) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.cd_new_list)) },
                                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showNewListDialog = true
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.cd_refresh)) },
                                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                onClick = {
                                    showOverflowMenu = false
                                    viewModel.loadItems(force = true)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.section_support)) },
                                leadingIcon = {
                                    Icon(Icons.Default.Favorite, contentDescription = null, tint = Color(0xFF9C27B0))
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    showSupportDialog = true
                                }
                            )
                        }
                    }
                }
            )
        },
        containerColor = TaskSurfaces.page,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (selectedList != null) {
                FloatingActionButton(
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = {
                        quickAddLauncher.launch(
                            Intent(context, QuickAddActivity::class.java).apply {
                                putExtra("entity_id", selectedList.entityId)
                                putExtra("list_name", selectedListName ?: selectedList.entityId)
                                putExtra("supported_features", selectedList.attributes.supportedFeatures ?: 0)
                                putExtra("from_app", true)
                            }
                        )
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add_item))
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(headerToEditorGap)
        ) {
            if (uiState.todoLists.isNotEmpty() || uiState.isLocalMode) {
                ListTabs(
                    lists = uiState.todoLists.map {
                        it.entityId to (it.attributes.friendlyName ?: it.entityId)
                    },
                    selectedId = uiState.selectedListId,
                    listState = listSelectorState,
                    onSelect = viewModel::selectList,
                    onReorder = viewModel::reorderLists,
                    onNewList = if (uiState.isLocalMode) {
                        { showNewListDialog = true }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            when {
                uiState.isLoadingLists -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                uiState.todoLists.isEmpty() && !uiState.isLoadingLists -> {
                    EmptyStateCard(
                        text = when {
                            uiState.allListsHidden -> stringResource(R.string.home_all_lists_hidden)
                            uiState.isLocalMode -> stringResource(R.string.home_no_lists_local)
                            else -> stringResource(R.string.home_no_lists_ha)
                        },
                        modifier = Modifier.padding(horizontal = AppSpacing.screenHorizontal)
                    )
                }
                selectedList == null -> {
                    EmptyStateCard(
                        text = stringResource(R.string.home_choose_list),
                        modifier = Modifier.padding(horizontal = AppSpacing.screenHorizontal)
                    )
                }
                else -> {
                    val pagerState = rememberPagerState(
                        initialPage = selectedListIndex,
                        pageCount = { uiState.todoLists.size }
                    )
                    val selectedListId by rememberUpdatedState(uiState.selectedListId)

                    LaunchedEffect(selectedListIndex, uiState.todoLists.size) {
                        if (uiState.todoLists.isNotEmpty() && pagerState.currentPage != selectedListIndex) {
                            pagerState.animateScrollToPage(selectedListIndex)
                        }
                    }

                    LaunchedEffect(pagerState, uiState.todoLists) {
                        snapshotFlow { pagerState.currentPage }
                            .collectLatest { page ->
                                val pageList = uiState.todoLists.getOrNull(page) ?: return@collectLatest
                                if (pageList.entityId != selectedListId) {
                                    viewModel.selectList(pageList.entityId)
                                }
                            }
                    }

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        beyondBoundsPageCount = 1
                    ) { page ->
                        val pageList = uiState.todoLists[page]
                        TodoListEditor(
                            context = context,
                            entityId = pageList.entityId,
                            listName = pageList.attributes.friendlyName ?: pageList.entityId,
                            supportedFeatures = pageList.attributes.supportedFeatures ?: 0,
                            httpClient = httpClient,
                            isLocalMode = uiState.isLocalMode,
                            showOpenAppAction = false,
                            onBack = {},
                            onChanged = { viewModel.loadItems(pageList.entityId, force = true) },
                            modifier = Modifier.fillMaxSize(),
                            showTopBar = false,
                            showListHeader = true,
                            onDeleteList = if (uiState.isLocalMode) {
                                {
                                    viewModel.selectList(pageList.entityId)
                                    showDeleteListDialog = true
                                }
                            } else null,
                            // Clears the floating add button so the last item stays reachable.
                            contentBottomPadding = 88.dp,
                            initialItems = uiState.itemsFor(pageList.entityId),
                            refreshOnLaunch = false,
                            onItemsChanged = { items ->
                                viewModel.updateCachedItems(pageList.entityId, items)
                            },
                            showAddRow = false,
                            moveTargets = uiState.todoLists.filter { it.entityId != pageList.entityId },
                            onItemMoved = { targetId -> viewModel.loadItems(targetId, force = true) }
                        )
                    }
                }
            }
        }
    }

    if (showNewListDialog) {
        NewListDialog(
            onDismiss = { showNewListDialog = false },
            onCreate = { name ->
                viewModel.createList(name)
                showNewListDialog = false
            }
        )
    }

    if (showSupportDialog) {
        AlertDialog(
            onDismissRequest = { showSupportDialog = false },
            title = { Text(stringResource(R.string.section_support)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.support_optional_donation),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FilledTonalButton(
                        onClick = {
                            openExternalUrl(context, "https://www.buymeacoffee.com/itbaer")
                            showSupportDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.label_buy_me_a_coffee))
                    }

                    OutlinedButton(
                        onClick = {
                            openExternalUrl(context, "https://www.paypal.com/donate/?hosted_button_id=5XXRC7THMTRRS")
                            showSupportDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.label_donate_paypal))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSupportDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showDeleteListDialog && uiState.selectedListId != null) {
        val listName = uiState.todoLists.find { it.entityId == uiState.selectedListId }
            ?.attributes?.friendlyName ?: uiState.selectedListId
        AlertDialog(
            onDismissRequest = { showDeleteListDialog = false },
            title = { Text(stringResource(R.string.dialog_delete_list_title)) },
            text = { Text(stringResource(R.string.dialog_delete_list_text, listName!!)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteList(uiState.selectedListId!!)
                    showDeleteListDialog = false
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteListDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

/** Google Tasks style list tabs: text tabs with a short rounded underline; long-press drags to reorder. */
@Composable
private fun ListTabs(
    lists: List<Pair<String, String>>,
    selectedId: String?,
    listState: LazyListState,
    onSelect: (String) -> Unit,
    onReorder: ((Int, Int) -> Unit)? = null,
    onNewList: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    val haptic = LocalHapticFeedback.current
    val currentLists by rememberUpdatedState(lists)

    Column(modifier = modifier) {
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            items(lists, key = { it.first }) { (id, name) ->
                val isDragging = draggedId == id
                ListTab(
                    text = name,
                    selected = id == selectedId,
                    onClick = { if (draggedId == null) onSelect(id) },
                    modifier = Modifier
                        .graphicsLayer {
                            if (isDragging) {
                                translationX = dragOffsetX
                                shadowElevation = 8f
                                shape = RoundedCornerShape(12.dp)
                                clip = true
                                scaleX = 1.05f
                                scaleY = 1.05f
                            }
                        }
                        .then(
                            if (isDragging) Modifier.background(TaskSurfaces.card) else Modifier
                        )
                        .then(
                            if (onReorder != null) {
                                Modifier.pointerInput(id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggedId = id
                                            dragOffsetX = 0f
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            if (draggedId == id) {
                                                dragOffsetX += dragAmount.x
                                                val liveLists = currentLists
                                                val currentIndex = liveLists.indexOfFirst { it.first == id }
                                                if (currentIndex < 0) return@detectDragGesturesAfterLongPress

                                                val itemInfo = listState.layoutInfo.visibleItemsInfo
                                                val draggedInfo = itemInfo.firstOrNull { it.key == id }
                                                val halfWidth = (draggedInfo?.size ?: 200) / 2

                                                val targetIndex = when {
                                                    dragOffsetX > halfWidth && currentIndex < liveLists.lastIndex -> currentIndex + 1
                                                    dragOffsetX < -halfWidth && currentIndex > 0 -> currentIndex - 1
                                                    else -> return@detectDragGesturesAfterLongPress
                                                }
                                                onReorder(currentIndex, targetIndex)
                                                dragOffsetX -= (targetIndex - currentIndex) * (draggedInfo?.size ?: 200).toFloat()
                                            }
                                        },
                                        onDragEnd = {
                                            draggedId = null
                                            dragOffsetX = 0f
                                        },
                                        onDragCancel = {
                                            draggedId = null
                                            dragOffsetX = 0f
                                        }
                                    )
                                }
                            } else Modifier
                        )
                )
            }
            if (onNewList != null) {
                item(key = "new-list-tab") {
                    Row(
                        modifier = Modifier
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onNewList)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.cd_new_list),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }
}

@Composable
private fun ListTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp)
            .width(IntrinsicSize.Max),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp)
            )
        }
        // Underline matches the label width, like Google Tasks.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)
                        )
                )
            }
        }
    }
}

private fun openExternalUrl(context: android.content.Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    context.startActivity(intent)
}

@Composable
private fun EmptyStateCard(
    text: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        shape = TaskSurfaces.cardShape,
        colors = CardDefaults.cardColors(containerColor = TaskSurfaces.card),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp)
        )
    }
}

@Composable
private fun NewListDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_new_list_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.label_list_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onCreate(text.trim()) },
                enabled = text.isNotBlank()
            ) { Text(stringResource(R.string.action_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
