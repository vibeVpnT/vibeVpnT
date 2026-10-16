package com.v2ray.ang.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.ui.compose.ConfirmDialog
import com.v2ray.ang.ui.compose.QRCodeDialog
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    onAction: (MainAction) -> Unit,
    onNavigate: (MainDestination) -> Unit,
) {
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val groups = uiState.groups
    val isLoading by mainViewModel.isLoading.collectAsStateWithLifecycle()
    val isRunning = uiState.isRunning
    val displayText = mainViewModel.formatStatus(uiState.status)
    val selectedGuid = uiState.selectedGuid
    val doubleColumnDisplay = uiState.doubleColumnDisplay
    val confirmRemove = uiState.confirmRemove
    val shareQRCodeBitmap = uiState.shareQRCodeBitmap

    // Выбранная вкладка переживает поворот экрана, но не перезапуск приложения:
    // открываться всегда должна главная.
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.Home) }

    val scope = rememberCoroutineScope()
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showDelAllConfirm by remember { mutableStateOf(false) }
    var showDelDuplicateConfirm by remember { mutableStateOf(false) }
    var showDelInvalidConfirm by remember { mutableStateOf(false) }
    var showRemoveConfirm by rememberSaveable(stateSaver = ServerDeleteTarget.Saver) {
        mutableStateOf<ServerDeleteTarget?>(null)
    }

    var addingSubscription by rememberSaveable { mutableStateOf(false) }
    var shareTarget by remember { mutableStateOf<Triple<String, ProfileItem, Boolean>?>(null) }
    val removeServer: (String, String) -> Unit = { guid, profileName ->
        if (confirmRemove) {
            showRemoveConfirm = ServerDeleteTarget(guid, profileName)
        } else {
            onAction(MainAction.RemoveServer(guid))
        }
    }

    // Подписку молча не заводим: при первом запуске спрашиваем,
    // нужен ли бесплатный триал, и только после согласия просим ключ.
    uiState.trialOffer?.let { offer ->
        ConfirmDialog(
            title = stringResource(R.string.trial_offer_title),
            message = if (offer.gb > 0) {
                stringResource(R.string.trial_offer_text, offer.days, offer.gb)
            } else {
                stringResource(R.string.trial_offer_text_unlimited, offer.days)
            },
            confirmText = stringResource(R.string.trial_offer_accept, offer.days),
            dismissText = stringResource(R.string.trial_offer_decline),
            onConfirm = { mainViewModel.claimTrial() },
            onDismiss = { mainViewModel.dismissTrialOffer() },
        )
    }

    // Срок на исходе. Показываем при каждом запуске, пока осталось мало:
    // приложение открывают не каждый день, и единственное уведомление
    // человек попросту пропустит.
    uiState.renewReminder?.let { daysLeft ->
        ConfirmDialog(
            title = stringResource(
                if (daysLeft > 0) R.string.renew_warn_title else R.string.renew_warn_title_over
            ),
            message = if (daysLeft > 0) {
                stringResource(
                    R.string.renew_warn_text,
                    pluralStringResource(R.plurals.renew_days, daysLeft, daysLeft),
                )
            } else {
                stringResource(R.string.renew_warn_text_over)
            },
            confirmText = stringResource(R.string.renew_warn_accept),
            dismissText = stringResource(R.string.renew_warn_decline),
            onConfirm = {
                mainViewModel.dismissRenewReminder()
                onNavigate(MainDestination.Renew)
            },
            onDismiss = { mainViewModel.dismissRenewReminder() },
        )
    }

    if (addingSubscription) {
        SubscriptionDialog(
            subscription = null,
            onSave = { item ->
                addingSubscription = false
                // Пустой groupId — признак новой подписки.
                mainViewModel.saveSubscription("", item)
            },
            onDismiss = { addingSubscription = false },
        )
    }

    MainDialogs(
        showDelAllConfirm = showDelAllConfirm,
        onDismissDelAll = { showDelAllConfirm = false },
        onConfirmDelAll = { showDelAllConfirm = false; onAction(MainAction.RemoveAllServers) },
        showDelDuplicateConfirm = showDelDuplicateConfirm,
        onDismissDelDuplicate = { showDelDuplicateConfirm = false },
        onConfirmDelDuplicate = { showDelDuplicateConfirm = false; onAction(MainAction.RemoveDuplicateServers) },
        showDelInvalidConfirm = showDelInvalidConfirm,
        onDismissDelInvalid = { showDelInvalidConfirm = false },
        onConfirmDelInvalid = { showDelInvalidConfirm = false; onAction(MainAction.RemoveInvalidServers) },
        showRemoveConfirm = showRemoveConfirm,
        onDismissRemove = { showRemoveConfirm = null },
        onConfirmRemove = { guid -> showRemoveConfirm = null; onAction(MainAction.RemoveServer(guid)) }
    )

    if (shareTarget != null) {
        val (guid, profile, more) = shareTarget!!
        ShareMethodDialog(
            guid = guid,
            profile = profile,
            more = more,
            onDismiss = { shareTarget = null },
            onAction = onAction,
            onRemove = removeServer,
        )
    }
    if (shareQRCodeBitmap != null) {
        QRCodeDialog(bitmap = shareQRCodeBitmap, onDismiss = { onAction(MainAction.DismissQRCodeDialog) })
    }

    Scaffold(
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
        topBar = {
            // Поиск и операции над списком нужны только на вкладке серверов.
            if (selectedTab == MainTab.Configs) {
                MainTopBar(
                    isLoading = isLoading,
                    showSearch = showSearch,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { query: String ->
                        searchQuery = query
                        onAction(MainAction.Search(query))
                    },
                    onSearchClose = {
                        searchQuery = ""
                        onAction(MainAction.Search(""))
                        showSearch = false
                    },
                    onSearchToggle = { show: Boolean -> showSearch = show },
                    onNavigate = onNavigate,
                    onAction = onAction,
                    onAddSubscription = { addingSubscription = true },
                    onMoreMenuAction = { action ->
                        when (action) {
                            MainMoreMenuAction.RestartService -> onAction(MainAction.RestartService)
                            MainMoreMenuAction.DeleteAll -> showDelAllConfirm = true
                            MainMoreMenuAction.DeleteDuplicate -> showDelDuplicateConfirm = true
                            MainMoreMenuAction.DeleteInvalid -> showDelInvalidConfirm = true
                            MainMoreMenuAction.ExportAll -> onAction(MainAction.ExportAll)
                            MainMoreMenuAction.LocateSelected -> onAction(MainAction.LocateSelectedServer)
                            MainMoreMenuAction.SortByTestResults -> onAction(MainAction.SortByTestResults)
                            MainMoreMenuAction.TestAll -> onAction(MainAction.TestAllServers)
                            MainMoreMenuAction.TestAllRealPing -> onAction(MainAction.TestRealAllServers)
                        }
                    }
                )
            }
        },
        bottomBar = {
            MainTabBar(selected = selectedTab, onSelect = { selectedTab = it })
        },
    ) { innerPadding ->
        val content = Modifier
            .fillMaxSize()
            .padding(innerPadding)

        when (selectedTab) {
            MainTab.Home -> HomeTab(
                isRunning = isRunning,
                statusText = displayText,
                serverName = uiState.selectedRemarks,
                backendStatus = uiState.backendStatus,
                backendChecking = uiState.backendChecking,
                onToggle = { onAction(MainAction.ToggleService) },
                onTestPing = { onAction(MainAction.TestCurrentServer) },
                onPickServer = { selectedTab = MainTab.Configs },
                modifier = content,
            )

            MainTab.Configs -> ConfigsTab(
                mainViewModel = mainViewModel,
                // Прячем только локальную группу, когда она пуста: раздел
                // «Local (0)» занимал строку, а нажать в нём было не на что.
                // Подписки показываем всегда, даже пустые — иначе только что
                // добавленную не видно, и обновить её негде: кнопка
                // обновления живёт в шапке группы.
                groups = groups.filter {
                    it.id.isNotEmpty() || (uiState.groupCounts[it.id] ?: 0) > 0
                },
                selectedGuid = selectedGuid,
                isTesting = uiState.isTesting,
                sortBy = uiState.sortBy,
                onSortChange = mainViewModel::setSort,
                isRunning = isRunning,
                statusText = displayText,
                searchQuery = searchQuery,
                onAction = onAction,
                onShareServer = { guid, profile -> shareTarget = Triple(guid, profile, false) },
                onMoreServer = { guid, profile -> shareTarget = Triple(guid, profile, true) },
                onRemoveServer = removeServer,
                modifier = content,
            )

            MainTab.Settings -> SettingsTab(
                supportEnabled = uiState.supportEnabled,
                renewEnabled = uiState.renewEnabled,
                ruDirect = uiState.ruDirect,
                onRuDirectChange = mainViewModel::setRuDirect,
                onNavigate = onNavigate,
                modifier = content,
            )
        }
    }
}
