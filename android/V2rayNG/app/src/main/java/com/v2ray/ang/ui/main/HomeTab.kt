package com.v2ray.ang.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.handler.MobileBackend
import com.v2ray.ang.ui.compose.Brand

/**
 * Главный экран: что подключено, как долго и одна кнопка.
 * Список серверов и настройки живут в соседних вкладках.
 */
@Composable
fun HomeTab(
    isRunning: Boolean,
    statusText: String,
    serverName: String,
    backendStatus: MobileBackend.Status?,
    backendChecking: Boolean,
    onToggle: () -> Unit,
    onTestPing: () -> Unit,
    onPickServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))

        // Название под знаком не пишем: оно и так есть под иконкой на
        // рабочем столе, а здесь только отнимало место у самого знака.
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = Brand.name,
            modifier = Modifier.size(168.dp),
        )

        Spacer(Modifier.weight(1f))

        PowerButton(isRunning = isRunning, onToggle = onToggle)

        Spacer(Modifier.height(18.dp))

        // Строку состояния пишет ядро: там же появляется результат замера
        // задержки, поэтому по ней можно нажать и перемерить.
        Text(
            text = statusText,
            color = Brand.onDarkMuted,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onTestPing)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )

        Spacer(Modifier.weight(1f))

        SubscriptionNotice(status = backendStatus, checking = backendChecking)

        SelectedServerCard(serverName = serverName, onClick = onPickServer)

        Spacer(Modifier.height(28.dp))
    }
}

/**
 * Единственный орган управления на экране.
 *
 * Выключено — знак питания, как на технике. Включено — надпись
 * «подключено»; нажатие в обоих состояниях переключает службу.
 */
@Composable
private fun PowerButton(isRunning: Boolean, onToggle: () -> Unit) {
    val ringColor by animateColorAsState(
        targetValue = if (isRunning) Brand.accent else Brand.disconnected,
        animationSpec = tween(350),
        label = "ring",
    )
    val label = stringResource(
        if (isRunning) R.string.home_action_disconnect else R.string.home_action_connect
    )

    Box(
        modifier = Modifier
            .size(176.dp)
            .clip(CircleShape)
            .background(Brand.card)
            .border(1.5.dp, ringColor, CircleShape)
            .clickable(onClick = onToggle)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (isRunning) {
            Text(
                text = stringResource(R.string.home_state_connected),
                color = Brand.accent,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_power_48dp),
                contentDescription = null,
                tint = Brand.onDarkMuted,
                modifier = Modifier.size(52.dp),
            )
        }
    }
}

/**
 * Строка о подписке: показывается, только когда с ней что-то не так.
 * Рабочую подписку объявлять незачем — об этом и так говорит экран.
 */
@Composable
private fun SubscriptionNotice(status: MobileBackend.Status?, checking: Boolean) {
    val messageRes = when {
        checking -> R.string.backend_checking
        status == null || status == MobileBackend.Status.ACTIVE -> null
        status == MobileBackend.Status.EXPIRED -> R.string.backend_expired
        status == MobileBackend.Status.LIMITED -> R.string.backend_limited
        status == MobileBackend.Status.DISABLED -> R.string.backend_disabled
        status == MobileBackend.Status.RATE_LIMITED -> R.string.backend_rate_limited
        status == MobileBackend.Status.UNAVAILABLE -> R.string.backend_unavailable
        status == MobileBackend.Status.NEEDS_ACCOUNT -> R.string.backend_needs_account
        else -> R.string.backend_network_error
    } ?: return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brand.card)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(messageRes),
            color = if (checking) Brand.onDarkMuted else Brand.onDark,
            fontSize = 13.sp,
        )
    }
}

/** Плитка выбранного сервера; нажатие уводит на вкладку со списком. */
@Composable
private fun SelectedServerCard(serverName: String, onClick: () -> Unit) {
    val empty = serverName.isBlank()
    val title = if (empty) stringResource(R.string.home_no_server) else serverName
    val hint = stringResource(R.string.home_pick_server)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Brand.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { contentDescription = "$hint: $title" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = hint, color = Brand.onDarkMuted, fontSize = 11.sp)
            Text(
                text = title,
                color = if (empty) Brand.onDarkMuted else Brand.onDark,
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
            )
        }
        Text(text = "›", color = Brand.onDarkMuted, fontSize = 20.sp)
    }
}
