package com.v2ray.ang.ui.main

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.ui.compose.Brand

/**
 * Вкладка «Серверы».
 *
 * Группы идут одним списком и раскрываются нажатием — вкладок сверху нет:
 * при двух-трёх подписках они занимали строку экрана, а переключаться
 * между ними всё равно приходилось постоянно.
 */
@Composable
fun ConfigsTab(
    mainViewModel: MainViewModel,
    groups: List<GroupMapItem>,
    selectedGuid: String?,
    isTesting: Boolean,
    sortBy: ServerSort,
    onSortChange: (ServerSort) -> Unit,
    isRunning: Boolean,
    statusText: String,
    searchQuery: String,
    onAction: (MainAction) -> Unit,
    onShareServer: (String, ProfileItem) -> Unit,
    onMoreServer: (String, ProfileItem) -> Unit,
    onRemoveServer: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Какие группы раскрыты. Состояние экранное: сворачивать их заново
    // при каждом возврате было бы навязчиво, а переживать перезапуск
    // приложения этому незачем.
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val listState = rememberLazyListState()
    // Какую подписку правим. null — окно закрыто.
    var editing by remember { mutableStateOf<GroupMapItem?>(null) }

    val actions = remember(onAction, onShareServer, onMoreServer, onRemoveServer) {
        ServerRowActions(
            select = { guid -> onAction(MainAction.SelectServer(guid)) },
            edit = { guid, profile -> onAction(MainAction.EditServer(guid, profile)) },
            share = onShareServer,
            more = onMoreServer,
            remove = onRemoveServer,
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        SortAndPingRow(
            sortBy = sortBy,
            onSortChange = onSortChange,
            isTesting = isTesting,
            onAction = onAction,
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            groups.forEach { group ->
                val groupStateFlow = mainViewModel.serverGroupState(group.id)

                item(key = "header-${group.id}") {
                    GroupHeader(
                        group = group,
                        groupStateFlow = groupStateFlow,
                        subscription = mainViewModel.subscriptionOf(group.id),
                        expanded = expanded[group.id] ?: true,
                        onToggle = { expanded[group.id] = !(expanded[group.id] ?: true) },
                        onUpdate = { onAction(MainAction.UpdateSubscriptions) },
                        onExport = { mainViewModel.exportGroup(group.id) },
                        onEdit = { editing = group },
                    )
                }

                if (expanded[group.id] != false) {
                    item(key = "rows-${group.id}") {
                        GroupRows(
                            groupStateFlow = groupStateFlow,
                            selectedGuid = selectedGuid,
                            actions = actions,
                        )
                    }
                }
            }
        }

        // Группа без записи подписки (локальная) правке не подлежит —
        // попап для неё просто не показываем. Сбрасывать editing прямо
        // здесь нельзя: запись состояния во время отрисовки гоняет
        // перерисовку по кругу.
        val editable = editing?.let { group ->
            mainViewModel.subscriptionOf(group.id)?.let { group.id to it }
        }
        editable?.let { (groupId, item) ->
            SubscriptionDialog(
                subscription = item,
                onSave = {
                    mainViewModel.saveSubscription(groupId, it)
                    editing = null
                },
                onDismiss = { editing = null },
            )
        }

        ConnectPanel(
            isRunning = isRunning,
            statusText = statusText,
            onToggle = { onAction(MainAction.ToggleService) },
            onRetest = { onAction(MainAction.TestCurrentServer) },
        )
    }
}

/**
 * Строка над списком: чем выстроен список и замер задержки всех серверов.
 *
 * Замер прямой — обычное TCP-соединение с адресом сервера, а не через
 * сам прокси. В подписи это не выносим: человеку важна цифра, а не
 * способ её получить.
 */
@Composable
private fun SortAndPingRow(
    sortBy: ServerSort,
    onSortChange: (ServerSort) -> Unit,
    isTesting: Boolean,
    onAction: (MainAction) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { menuOpen = true }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.sort_by),
                    color = Brand.onDark,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(sortBy.labelRes),
                    color = Brand.accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = Brand.menu,
            ) {
                ServerSort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(option.labelRes),
                                color = if (option == sortBy) Brand.accent else Brand.onDark,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onSortChange(option)
                        },
                    )
                }
            }
        }

        Text(
            text = stringResource(
                if (isTesting) R.string.action_cancel else R.string.ping_all
            ),
            color = Brand.accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    onAction(
                        if (isTesting) MainAction.CancelTesting
                        else MainAction.TestAllServers
                    )
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** Подпись типа сортировки. */
private val ServerSort.labelRes: Int
    get() = when (this) {
        ServerSort.Ping -> R.string.sort_by_ping
        ServerSort.Name -> R.string.sort_by_name
        ServerSort.Manual -> R.string.sort_by_manual
    }

/**
 * Шапка группы: название, число серверов, остаток трафика и срок.
 *
 * Пустые группы сюда не доходят — их отсеивают выше: раздел вроде
 * «Local (0)» занимал место, но нажимать в нём было не на что.
 */
@Composable
private fun GroupHeader(
    group: GroupMapItem,
    groupStateFlow: kotlinx.coroutines.flow.StateFlow<ServerGroupUiState>,
    subscription: SubscriptionItem?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onUpdate: () -> Unit,
    onExport: () -> Unit,
    onEdit: () -> Unit,
) {
    val state by groupStateFlow.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brand.card)
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_restore_24dp),
                contentDescription = stringResource(R.string.title_sub_update),
                tint = Brand.accent,
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .clickable(onClick = onUpdate),
            )
            Text(
                text = "${group.remarks} (${state.rows.size})",
                color = Brand.onDark,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            )

            Box {
                Icon(
                    painter = painterResource(R.drawable.ic_about_24dp),
                    contentDescription = stringResource(R.string.acc_more),
                    tint = Brand.accent,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .clickable { menuOpen = true },
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = Brand.menu,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.title_export_all), color = Brand.onDark) },
                        onClick = { menuOpen = false; onExport() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sub_edit_title), color = Brand.onDark) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                }
            }
            Icon(
                painter = painterResource(R.drawable.ic_expand_more_24dp),
                contentDescription = null,
                tint = Brand.onDarkMuted,
                modifier = Modifier
                    .size(22.dp)
                    .padding(start = 2.dp)
                    .rotate(if (expanded) 180f else 0f),
            )
        }

        // Полоса показывается, только если панель прислала цифры: у
        // подписок, добавленных вручную, их может не быть вовсе.
        if (subscription != null && (subscription.usedTraffic > 0 || subscription.totalTraffic > 0)) {
            Spacer(Modifier.height(10.dp))
            TrafficBar(subscription)
        }
    }
}

/** Потрачено, осталось и до какого числа. */
@Composable
private fun TrafficBar(subscription: SubscriptionItem) {
    val used = subscription.usedTraffic
    val total = subscription.totalTraffic
    val share = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Brand.surface),
        ) {
            if (share > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(share)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Brand.accent),
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (total > 0) {
                    stringResource(R.string.sub_traffic_of, formatBytes(used), formatBytes(total))
                } else {
                    // Безлимит: остаток считать не от чего, показываем расход.
                    stringResource(R.string.sub_traffic_unlimited, formatBytes(used))
                },
                color = Brand.accent,
                fontSize = 12.sp,
            )
            Text(
                text = formatExpiry(subscription.expireAt),
                color = Brand.onDarkMuted,
                fontSize = 12.sp,
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (value >= 100 || unit == 0) "%.0f %s".format(value, units[unit])
    else "%.1f %s".format(value, units[unit])
}

@Composable
private fun formatExpiry(expireAt: Long): String {
    if (expireAt <= 0) return stringResource(R.string.sub_no_expiry)

    val left = expireAt * 1000 - System.currentTimeMillis()
    if (left <= 0) return stringResource(R.string.sub_expired)

    return stringResource(R.string.sub_days_left, (left / 86_400_000L).toInt() + 1)
}

/**
 * Серверы одной группы.
 *
 * Все группы лежат в одном прокручиваемом списке, поэтому строки идут
 * обычной колонкой: вложить второй LazyColumn внутрь первого нельзя.
 */
@Composable
private fun GroupRows(
    groupStateFlow: kotlinx.coroutines.flow.StateFlow<ServerGroupUiState>,
    selectedGuid: String?,
    actions: ServerRowActions,
) {
    val state by groupStateFlow.collectAsStateWithLifecycle()

    Column(modifier = Modifier.animateContentSize()) {
        state.rows.forEach { row ->
            ServerListItem(
                row = row,
                isSelected = row.guid == selectedGuid,
                doubleColumnDisplay = false,
                actions = actions,
            )
        }
    }
}

/**
 * Панель подключения внизу экрана.
 *
 * Пока не подключено — короткая надпись. Как подключились, здесь
 * появляется результат проверки соединения: задержка, страна и адрес,
 * с которым человека видят снаружи. Значок обновления рядом перезапускает
 * проверку.
 */
@Composable
private fun ConnectPanel(
    isRunning: Boolean,
    statusText: String,
    onToggle: () -> Unit,
    onRetest: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brand.surface)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // Мельче основного текста: здесь две строки подробностей
                // рядом с кнопкой, и крупный шрифт их не вмещает.
                text = statusText,
                color = Brand.onDarkMuted,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isRunning) {
                Icon(
                    painter = painterResource(R.drawable.ic_restore_24dp),
                    contentDescription = stringResource(R.string.connection_test_pending),
                    tint = Brand.accent,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(18.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .clickable(onClick = onRetest),
                )
            }
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(if (isRunning) Brand.accent else Brand.background)
                .clickable(onClick = onToggle)
                .padding(horizontal = 24.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(
                    if (isRunning) R.string.home_action_disconnect
                    else R.string.home_action_connect
                ),
                color = if (isRunning) Brand.background else Brand.onDark,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
