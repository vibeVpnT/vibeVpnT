package com.v2ray.ang.ui.main

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.AppUpdater
import com.v2ray.ang.ui.compose.AppDivider
import com.v2ray.ang.ui.compose.Brand
import com.v2ray.ang.util.Utils

/** Три вкладки внизу: главная, серверы, настройки. */
enum class MainTab(@DrawableRes val iconRes: Int, @StringRes val labelRes: Int) {
    Home(R.drawable.ic_tab_home_24dp, R.string.tab_home),
    Configs(R.drawable.ic_tab_servers_24dp, R.string.tab_configs),
    Settings(R.drawable.ic_settings_24dp, R.string.tab_settings),
}

@Composable
fun MainTabBar(selected: MainTab, onSelect: (MainTab) -> Unit) {
    NavigationBar(containerColor = Brand.surface) {
        MainTab.entries.forEach { tab ->
            val label = stringResource(tab.labelRes)
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = {
                    Icon(
                        painter = painterResource(tab.iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                },
                label = { Text(label, fontSize = 11.sp) },
                // Как в V2Box: выбранная вкладка отличается только цветом,
                // подложки-пилюли под ней нет.
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Brand.accent,
                    selectedTextColor = Brand.accent,
                    indicatorColor = Color.Transparent,
                    unselectedIconColor = Brand.onDark,
                    unselectedTextColor = Brand.onDark,
                ),
            )
        }
    }
}

/**
 * Вкладка настроек. Разделы те же, что раньше открывались из бокового меню —
 * оно убрано, потому что в V2Box-подобной раскладке его место занял
 * нижний ряд вкладок.
 */
@Composable
fun SettingsTab(
    supportEnabled: Boolean,
    renewEnabled: Boolean,
    ruDirect: Boolean,
    onRuDirectChange: (Boolean) -> Unit,
    onNavigate: (MainDestination) -> Unit,
    update: AppUpdater.Update?,
    updateProgress: Int?,
    onUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Продление появляется, только если оно настроено на бэкенде:
    // пункт, который открывает экран «продлить нельзя», не нужен никому.
    val primary = buildList {
        add(MainDestination.Account)
        if (renewEnabled) add(MainDestination.Renew)
        addAll(
            listOf(
                MainDestination.Subscriptions,
                MainDestination.PerAppProxy,
                MainDestination.Routing,
                MainDestination.UserAssets,
                MainDestination.Settings,
                MainDestination.LinkDevice,
            )
        )
    }
    // Чат появляется, только если на бэкенде задана группа поддержки:
    // иначе писать было бы некуда.
    val secondary = buildList {
        if (supportEnabled) add(MainDestination.Support)
        add(MainDestination.Logcat)
        add(MainDestination.BackupRestore)
        add(MainDestination.About)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        // Только когда есть сборка новее: кнопка, которая ничего не
        // обновляет, — лишний шум.
        if (update != null) {
            UpdateCard(update = update, progress = updateProgress, onUpdate = onUpdate)
            Spacer(Modifier.height(16.dp))
        }
        RuDirectSwitch(checked = ruDirect, onChange = onRuDirectChange)

        Spacer(Modifier.height(16.dp))
        SettingsGroup(primary, onNavigate)

        Spacer(Modifier.height(16.dp))
        SettingsGroup(secondary, onNavigate)

        // Ссылки бренда: показываем только те, что заданы в brand.properties.
        // Ссылка на поддержку в Telegram нужна только как запасной путь:
        // когда чат работает, второй вход в поддержку лишь путает.
        val links = buildList {
            if (!supportEnabled && Brand.supportUrl.isNotBlank()) {
                add(R.string.brand_support to Brand.supportUrl)
            }
            if (Brand.siteUrl.isNotBlank()) add(R.string.brand_site to Brand.siteUrl)
        }
        if (links.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brand.card)
            ) {
                links.forEachIndexed { index, (titleRes, url) ->
                    if (index > 0) AppDivider()
                    val title = stringResource(titleRes)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { Utils.openUri(context, url) }
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(title, color = Brand.onDark, fontSize = 15.sp)
                        Text("›", color = Brand.onDarkMuted, fontSize = 20.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = "${Brand.name} ${BuildConfig.VERSION_NAME}",
            color = Brand.onDarkMuted,
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/**
 * Российские сервисы мимо VPN.
 *
 * Включено по умолчанию, и это не про экономию трафика: банки, госуслуги
 * и доставка отказывают адресам дата-центров, и через сервер попросту не
 * открываются. Выключать стоит, только если нужно, чтобы вообще всё шло
 * через сервер.
 */
/** Доступна новая версия: что за версия, когда вышла, и кнопка. */
@Composable
private fun UpdateCard(update: AppUpdater.Update, progress: Int?, onUpdate: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brand.card)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.update_available_title, update.versionName),
                color = Brand.onDark,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            if (update.releasedAt.isNotBlank()) {
                Text(
                    text = stringResource(R.string.update_available_date, update.releasedAt),
                    color = Brand.onDarkMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        Text(
            text = if (progress == null) {
                stringResource(R.string.update_button)
            } else {
                stringResource(R.string.update_downloading, progress)
            },
            color = Brand.background,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Brand.accent)
                .clickable(enabled = progress == null, onClick = onUpdate)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun RuDirectSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brand.card)
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.routing_ru_direct_title),
                color = Brand.onDark,
                fontSize = 15.sp,
            )
            Text(
                text = stringResource(R.string.routing_ru_direct_hint),
                color = Brand.onDarkMuted,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(top = 1.dp, end = 12.dp),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.scale(0.8f),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Brand.background,
                checkedTrackColor = Brand.accent,
                uncheckedThumbColor = Brand.onDarkMuted,
                uncheckedTrackColor = Brand.surface,
            ),
        )
    }
}

@Composable
private fun SettingsGroup(items: List<MainDestination>, onNavigate: (MainDestination) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brand.card)
    ) {
        items.forEachIndexed { index, item ->
            if (index > 0) AppDivider()
            val label = stringResource(item.labelRes)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigate(item) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(item.iconRes),
                    contentDescription = null,
                    tint = Brand.accent,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = label,
                    color = Brand.onDark,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp),
                )
                Text("›", color = Brand.onDarkMuted, fontSize = 20.sp)
            }
        }
    }
}
