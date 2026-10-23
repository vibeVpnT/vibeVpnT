package com.v2ray.ang.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.ui.compose.AppDialog
import com.v2ray.ang.ui.compose.Brand
import com.v2ray.ang.ui.compose.dialogFieldColors

/**
 * Подписка: заведение новой и правка существующей.
 *
 * На виду только название и адрес — больше при добавлении обычно ничего
 * и не нужно: ссылку присылают готовой. Остальное убрано под стрелку,
 * чтобы попап открывался коротким, а редкие настройки никуда не делись.
 *
 * @param subscription что правим; null — заводим новую.
 */
@Composable
fun SubscriptionDialog(
    subscription: SubscriptionItem?,
    onSave: (SubscriptionItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val original = subscription ?: SubscriptionItem()

    var remarks by rememberSaveable { mutableStateOf(original.remarks) }
    var url by rememberSaveable { mutableStateOf(original.url) }
    var enabled by rememberSaveable { mutableStateOf(original.enabled) }
    var autoUpdate by rememberSaveable { mutableStateOf(original.autoUpdate) }
    var interval by rememberSaveable { mutableStateOf(original.updateInterval.toString()) }
    var userAgent by rememberSaveable { mutableStateOf(original.userAgent.orEmpty()) }
    var filter by rememberSaveable { mutableStateOf(original.filter.orEmpty()) }
    var expanded by rememberSaveable { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(
            if (subscription == null) R.string.sub_add_title else R.string.sub_edit_title
        ),
        confirmText = stringResource(R.string.action_save),
        confirmEnabled = remarks.isNotBlank() && url.isNotBlank(),
        onConfirm = {
            onSave(
                original.copy(
                    remarks = remarks.trim(),
                    url = url.trim(),
                    enabled = enabled,
                    autoUpdate = autoUpdate,
                    // Чаще минуты в четверть часа обновлять нельзя, да и незачем.
                    updateInterval = interval.toLongOrNull()?.coerceAtLeast(15) ?: 1440,
                    userAgent = userAgent.trim().ifBlank { null },
                    filter = filter.trim().ifBlank { null },
                )
            )
        },
        dismissText = stringResource(R.string.action_cancel),
    ) {
        Column(modifier = Modifier.verticalScroll(scrollState)) {
            OutlinedTextField(
                value = remarks,
                onValueChange = { remarks = it },
                label = { Text(stringResource(R.string.sub_edit_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = dialogFieldColors(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.sub_edit_url)) },
                // Адрес длинный, в одну строку не влезает.
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                colors = dialogFieldColors(),
            )

            Spacer(Modifier.height(4.dp))
            ExpandRow(expanded = expanded, onToggle = { expanded = !expanded })

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SwitchRow(
                        title = stringResource(R.string.sub_setting_enable),
                        checked = enabled,
                        onChange = { enabled = it },
                    )
                    SwitchRow(
                        title = stringResource(R.string.sub_auto_update),
                        checked = autoUpdate,
                        onChange = { autoUpdate = it },
                    )
                    if (autoUpdate) {
                        OutlinedTextField(
                            value = interval,
                            onValueChange = { interval = it.filter(Char::isDigit) },
                            label = { Text(stringResource(R.string.title_pref_auto_update_interval)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            colors = dialogFieldColors(),
                        )
                    }
                    OutlinedTextField(
                        value = userAgent,
                        onValueChange = { userAgent = it },
                        label = { Text(stringResource(R.string.sub_setting_user_agent)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = dialogFieldColors(),
                    )
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        label = { Text(stringResource(R.string.sub_setting_filter)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = dialogFieldColors(),
                    )
                }
            }
        }
    }
}

/** Строка «Больше настроек» со стрелкой, которая переворачивается. */
@Composable
private fun ExpandRow(expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.sub_more_settings),
            color = Brand.onDarkMuted,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(R.drawable.ic_expand_more_24dp),
            contentDescription = null,
            tint = Brand.onDarkMuted,
            modifier = Modifier
                .size(20.dp)
                .rotate(if (expanded) 180f else 0f),
        )
    }
}

/** Тумблер внутри попапа: той же высоты, что и поля рядом. */
@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onChange(!checked) }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = Brand.onDark,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
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
