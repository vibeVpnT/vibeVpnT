package com.v2ray.ang.ui.main

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.Brand
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.ui.compose.AppDropdownMenuItems
import com.v2ray.ang.ui.compose.SelectListDialog

/**
 * Первый уровень меню «+»: частые действия, а длинный список протоколов
 * убран на второй уровень. Плоский список из тринадцати пунктов, который
 * был раньше, в маленьком меню не читался.
 */
private enum class ImportMenuAction(
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
    val action: MainAction,
) {
    Clipboard(R.string.menu_item_import_config_clipboard, R.drawable.ic_copy, MainAction.ImportClipboard),
    LocalFile(R.string.menu_item_import_config_local, R.drawable.ic_file_24dp, MainAction.ImportConfigLocal),
    QRCode(R.string.menu_item_import_config_qrcode, R.drawable.ic_scan_24dp, MainAction.ImportQRcode),
    UpdateSubs(R.string.menu_item_update_all_subs, R.drawable.ic_restore_24dp, MainAction.UpdateSubscriptions),
}

/** Второй уровень: чем именно заполнять профиль, заводимый руками. */
private enum class ManualProfile(@StringRes val labelRes: Int, val type: Int) {
    Vmess(R.string.proto_vmess, EConfigType.VMESS.value),
    Vless(R.string.proto_vless, EConfigType.VLESS.value),
    Trojan(R.string.proto_trojan, EConfigType.TROJAN.value),
    Shadowsocks(R.string.proto_shadowsocks, EConfigType.SHADOWSOCKS.value),
    Socks(R.string.proto_socks, EConfigType.SOCKS.value),
    Http(R.string.proto_http, EConfigType.HTTP.value),
    WireGuard(R.string.proto_wireguard, EConfigType.WIREGUARD.value),
    Hysteria2(R.string.proto_hysteria2, EConfigType.HYSTERIA2.value),
    PolicyGroup(R.string.proto_policy_group, EConfigType.POLICYGROUP.value),
    ProxyChain(R.string.proto_proxy_chain, EConfigType.PROXYCHAIN.value),
}

enum class MainMoreMenuAction(@StringRes val labelRes: Int) {
    RestartService(R.string.title_service_restart),
    DeleteAll(R.string.title_del_all_config),
    DeleteDuplicate(R.string.title_del_duplicate_config),
    DeleteInvalid(R.string.title_del_invalid_config),
    ExportAll(R.string.title_export_all),
    LocateSelected(R.string.title_locate_selected_config),
    SortByTestResults(R.string.title_sort_by_test_results),
    TestAll(R.string.title_ping_all_server),
    TestAllRealPing(R.string.title_real_ping_all_server),
}

internal enum class ServerMenuAction(
    @StringRes val labelRes: Int,
    val isShareAction: Boolean,
    val supportsComplexProfiles: Boolean,
) {
    ShareQRCode(R.string.share_method_qrcode, isShareAction = true, supportsComplexProfiles = false),
    ShareClipboard(R.string.share_method_clipboard, isShareAction = true, supportsComplexProfiles = false),
    ShareFullContent(R.string.share_method_full_content, isShareAction = true, supportsComplexProfiles = true),
    Edit(R.string.action_edit, isShareAction = false, supportsComplexProfiles = true),
    Delete(R.string.action_delete, isShareAction = false, supportsComplexProfiles = true),
}

internal fun serverMenuActions(
    isComplexProfile: Boolean,
    includeManagementActions: Boolean,
): List<ServerMenuAction> = ServerMenuAction.entries.filter { action ->
    (includeManagementActions || action.isShareAction) && (!isComplexProfile || action.supportsComplexProfiles)
}

/**
 * Содержимое меню «+».
 *
 * Два уровня внутри одного выпадающего списка: нажатие на «добавить
 * вручную» подменяет список протоколами, сверху остаётся приглушённый
 * заголовок — нажатие на него возвращает обратно. Compose не умеет
 * вложенные меню, а второе всплывающее окно поверх первого на телефоне
 * не помещается.
 */
@Composable
fun ImportMenuContent(
    showManual: Boolean,
    onShowManual: (Boolean) -> Unit,
    onAction: (MainAction) -> Unit,
    onAddSubscription: () -> Unit,
) {
    if (showManual) {
        MenuRow(
            labelRes = R.string.menu_item_add_manually,
            iconRes = R.drawable.ic_arrow_back_24dp,
            muted = true,
            onClick = { onShowManual(false) },
        )
        ManualProfile.entries.forEach { profile ->
            MenuRow(
                labelRes = profile.labelRes,
                onClick = { onAction(MainAction.ImportManually(profile.type)) },
            )
        }
        return
    }

    ImportMenuAction.entries.forEach { item ->
        MenuRow(
            labelRes = item.labelRes,
            iconRes = item.iconRes,
            onClick = { onAction(item.action) },
        )
    }
    MenuRow(
        labelRes = R.string.menu_item_add_subscription,
        iconRes = R.drawable.ic_subscriptions_24dp,
        onClick = onAddSubscription,
    )
    MenuRow(
        labelRes = R.string.menu_item_add_manually,
        iconRes = R.drawable.ic_tab_servers_24dp,
        trailing = "›",
        onClick = { onShowManual(true) },
    )
}

@Composable
private fun MenuRow(
    @StringRes labelRes: Int,
    @DrawableRes iconRes: Int? = null,
    trailing: String? = null,
    muted: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (muted) Brand.onDarkMuted else Brand.onDark
    DropdownMenuItem(
        text = { Text(stringResource(labelRes), color = color) },
        leadingIcon = iconRes?.let {
            {
                Icon(
                    painter = painterResource(it),
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        trailingIcon = trailing?.let { { Text(it, color = Brand.onDarkMuted) } },
        onClick = onClick,
    )
}

@Composable
fun MoreMenuContent(onSelected: (MainMoreMenuAction) -> Unit) = AppDropdownMenuItems(
    items = MainMoreMenuAction.entries,
    labelRes = { it.labelRes },
    onSelected = onSelected
)

@Composable
fun ShareMethodDialog(
    guid: String,
    profile: ProfileItem,
    more: Boolean,
    onDismiss: () -> Unit,
    onAction: (MainAction) -> Unit,
    onRemove: (String, String) -> Unit,
) {
    val menuActions = serverMenuActions(
        isComplexProfile = profile.configType.isComplexType(),
        includeManagementActions = more,
    )
    SelectListDialog(
        options = menuActions,
        optionText = { stringResource(it.labelRes) },
        onSelected = { action ->
            onDismiss()
            when (action) {
                ServerMenuAction.ShareQRCode -> onAction(MainAction.ShareQRCode(guid))
                ServerMenuAction.ShareClipboard -> onAction(MainAction.ShareClipboard(guid))
                ServerMenuAction.ShareFullContent -> onAction(MainAction.ShareFullContent(guid))
                ServerMenuAction.Edit -> onAction(MainAction.EditServer(guid, profile))
                ServerMenuAction.Delete -> onRemove(guid, profile.remarks)
            }
        },
        onDismiss = onDismiss
    )
}
