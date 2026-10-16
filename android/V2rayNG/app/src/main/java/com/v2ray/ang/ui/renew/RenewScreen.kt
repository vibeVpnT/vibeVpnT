package com.v2ray.ang.ui.renew

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.handler.MobileBackend
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.Brand
import com.v2ray.ang.util.Utils

/**
 * Экран «Подписка»: сколько осталось, тарифы, оплата и чек.
 *
 * Порядок на экране тот же, что в голове у человека: сначала «сколько у
 * меня осталось», потом «что с моим чеком», и только потом «на сколько
 * продлить».
 */
@Composable
fun RenewScreen(
    viewModel: RenewViewModel,
    onBackClick: () -> Unit,
    onPickReceipt: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.load()
        viewModel.startPolling()
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.title_renew),
                onBackClick = onBackClick,
                isLoading = state.sending,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
        ) {
            Spacer(Modifier.height(12.dp))

            when {
                state.loading -> Box(
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Brand.accent)
                }

                state.offline || state.state == null -> Card {
                    Title(stringResource(R.string.renew_offline_title))
                    Hint(stringResource(R.string.renew_offline_hint))
                }

                else -> Content(
                    state = state,
                    onPickTier = viewModel::pickTier,
                    onBack = viewModel::backToTiers,
                    onPay = { url -> Utils.openUri(context, url) },
                    onPickReceipt = onPickReceipt,
                )
            }

            state.failureText.takeIf { it.isNotBlank() }?.let { text ->
                Spacer(Modifier.height(10.dp))
                Card { Hint(text) }
            }

            if (state.failure == RenewViewModel.Failure.TOO_BIG) {
                Spacer(Modifier.height(10.dp))
                Card { Hint(stringResource(R.string.renew_receipt_too_big)) }
            }
            if (state.failure == RenewViewModel.Failure.UNREADABLE) {
                Spacer(Modifier.height(10.dp))
                Card { Hint(stringResource(R.string.renew_receipt_unreadable)) }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Content(
    state: RenewViewModel.UiState,
    onPickTier: (MobileBackend.Tier) -> Unit,
    onBack: () -> Unit,
    onPay: (String) -> Unit,
    onPickReceipt: () -> Unit,
) {
    val renew = state.state ?: return

    SubscriptionCard(renew.subscription)

    renew.request?.let { request ->
        Spacer(Modifier.height(10.dp))
        RequestCard(request, renew.auto_approve_minutes)
    }

    val waiting = renew.request?.isOpen == true

    when {
        // Пока заявка жива, выбирать новый тариф не из чего.
        waiting -> Unit

        !renew.renew_enabled -> {
            Spacer(Modifier.height(10.dp))
            Card {
                Title(stringResource(R.string.renew_disabled_title))
                Hint(stringResource(R.string.renew_disabled_hint))
            }
        }

        state.chosen != null -> {
            Spacer(Modifier.height(10.dp))
            PayCard(
                tier = state.chosen,
                payUrl = renew.pay_url,
                requisites = renew.requisites,
                sending = state.sending,
                onPay = onPay,
                onPickReceipt = onPickReceipt,
                onBack = onBack,
            )
        }

        else -> {
            Spacer(Modifier.height(18.dp))
            Hint(stringResource(R.string.renew_pick_hint))
            Spacer(Modifier.height(8.dp))
            renew.tiers.forEach { tier ->
                TierCard(tier) { onPickTier(tier) }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SubscriptionCard(info: MobileBackend.SubscriptionInfo?) {
    Card {
        Title(stringResource(R.string.renew_my_subscription))

        if (info == null) {
            Hint(stringResource(R.string.renew_no_key))
            return@Card
        }

        Hint(leftText(info))
        Hint(trafficText(info))
        if (info.username.isNotBlank()) {
            Hint(stringResource(R.string.renew_key, info.username))
        }
    }
}

@Composable
private fun leftText(info: MobileBackend.SubscriptionInfo): String = when {
    info.starts_on_first_use -> stringResource(R.string.renew_left_not_started)
    info.days_left == null -> stringResource(R.string.renew_left_unlimited)
    info.days_left <= 0 -> stringResource(R.string.renew_left_expired)
    else -> stringResource(R.string.renew_left_days, daysWord(info.days_left))
}

@Composable
private fun trafficText(info: MobileBackend.SubscriptionInfo): String {
    if (info.data_limit <= 0) {
        return stringResource(R.string.renew_traffic_unlimited, gb(info.used_traffic))
    }
    val left = (info.data_limit - info.used_traffic).coerceAtLeast(0)
    return stringResource(R.string.renew_traffic, gb(left), gb(info.data_limit))
}

@Composable
private fun RequestCard(request: MobileBackend.RenewRequest, autoApproveMinutes: Int) {
    val title: String
    val hint: String

    when {
        request.isApproved -> {
            title = stringResource(R.string.renew_state_done_title)
            hint = if (request.days != null && request.days > 0) {
                stringResource(R.string.renew_state_done_hint, daysWord(request.days), request.gb ?: 0)
            } else {
                stringResource(R.string.renew_state_done_plain)
            }
        }

        request.status == "rejected" -> {
            title = stringResource(R.string.renew_state_rejected_title)
            hint = stringResource(R.string.renew_state_rejected_hint)
        }

        request.status == "rolled_back" -> {
            title = stringResource(R.string.renew_state_rolled_back_title)
            hint = stringResource(R.string.renew_state_rejected_hint)
        }

        request.status == "manual" -> {
            title = stringResource(R.string.renew_state_manual_title)
            hint = stringResource(R.string.renew_state_manual_hint)
        }

        request.status == "failed" -> {
            title = stringResource(R.string.renew_state_failed_title)
            hint = stringResource(R.string.renew_state_failed_hint)
        }

        // Ждём решения. Пока чек не отнесли админам, честнее писать
        // «отправляем», а не «ждём ответа».
        !request.delivered -> {
            title = stringResource(R.string.renew_state_sending_title)
            hint = stringResource(R.string.renew_state_sending_hint)
        }

        else -> {
            title = stringResource(R.string.renew_state_waiting_title)
            hint = if (autoApproveMinutes > 0 && (request.days ?: 0) > 0) {
                stringResource(R.string.renew_state_waiting_auto, autoApproveMinutes)
            } else {
                stringResource(R.string.renew_state_waiting_hint)
            }
        }
    }

    Card {
        Title(title)
        Hint(hint)
    }
}

@Composable
private fun TierCard(tier: MobileBackend.Tier, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brand.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = buildString {
                if (tier.emoji.isNotBlank()) append(tier.emoji).append(' ')
                append(daysWord(tier.days))
                if (tier.gb > 0) append(" + ").append(tier.gb).append(" ГБ")
            },
            color = Brand.onDark,
            fontSize = 15.sp,
        )
        Text(
            text = stringResource(R.string.renew_amount, tier.amount),
            color = Brand.accent,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun PayCard(
    tier: MobileBackend.Tier,
    payUrl: String,
    requisites: String,
    sending: Boolean,
    onPay: (String) -> Unit,
    onPickReceipt: () -> Unit,
    onBack: () -> Unit,
) {
    Card {
        Title(
            stringResource(
                R.string.renew_pay_title,
                tier.amount,
                daysWord(tier.days),
            )
        )
        Hint(
            if (payUrl.isNotBlank()) {
                stringResource(R.string.renew_pay_hint)
            } else {
                stringResource(R.string.renew_pay_hint_requisites)
            }
        )
        if (requisites.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(requisites, color = Brand.onDark, fontSize = 14.sp)
        }
    }

    Spacer(Modifier.height(10.dp))

    if (payUrl.isNotBlank()) {
        Button(
            onClick = { onPay(payUrl) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Brand.accentMuted,
                contentColor = Brand.accent,
            ),
        ) {
            Text(stringResource(R.string.renew_pay_action), fontSize = 15.sp)
        }
        Spacer(Modifier.height(8.dp))
    }

    Button(
        onClick = onPickReceipt,
        enabled = !sending,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = Brand.accentMuted,
            contentColor = Brand.accent,
        ),
    ) {
        Text(
            text = stringResource(
                if (sending) R.string.renew_receipt_sending else R.string.renew_receipt_action
            ),
            fontSize = 15.sp,
        )
    }

    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.renew_back), color = Brand.onDarkMuted, fontSize = 14.sp)
    }
}

// --- Мелочи вёрстки ---------------------------------------------------------

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brand.card)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        content = content,
    )
}

@Composable
private fun Title(text: String) {
    Text(text, color = Brand.onDark, fontSize = 15.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        color = Brand.onDarkMuted,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** «1 день», «2 дня», «5 дней» — склонение делает система. */
@Composable
private fun daysWord(days: Int): String =
    pluralStringResource(R.plurals.renew_days, days, days)

private fun gb(bytes: Long): String {
    val value = bytes.toDouble() / (1024 * 1024 * 1024)
    return if (value >= 10) value.toInt().toString() else String.format("%.1f", value)
}
