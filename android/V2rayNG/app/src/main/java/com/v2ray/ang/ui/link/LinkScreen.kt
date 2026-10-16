package com.v2ray.ang.ui.link

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.Brand

@Composable
fun LinkScreen(
    viewModel: LinkViewModel,
    onBackClick: () -> Unit,
    onEntered: () -> Unit,
    onScanClick: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }

    // Нас впустили — возвращаемся к списку серверов, они уже загружены.
    LaunchedEffect(state.entered) {
        if (state.entered) onEntered()
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.title_link_device),
                onBackClick = onBackClick,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            ShowCodeCard(state = state, onIssue = viewModel::issueCode)

            Spacer(Modifier.height(16.dp))
            GrantCard(
                state = state,
                draft = draft,
                onDraftChange = { value ->
                    draft = value.filter { it.isDigit() }.take(LinkViewModel.CODE_LENGTH)
                },
                onScanClick = onScanClick,
                onSubmit = { viewModel.grantCode(draft) },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Половина «войти здесь»: её открывают на устройстве без подписки.
 *
 * Код показывается на том экране, за которым сидит входящий, — чтобы
 * снять его чужой камерой, как в Steam и Telegram.
 */
@Composable
private fun ShowCodeCard(state: LinkViewModel.UiState, onIssue: () -> Unit) {
    Card {
        Text(
            text = stringResource(R.string.link_show_title),
            color = Brand.onDark,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.link_show_hint),
            color = Brand.onDarkMuted,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(14.dp))

        when {
            state.issuing -> Box(Modifier.fillMaxWidth(), Alignment.Center) {
                CircularProgressIndicator(color = Brand.accent, modifier = Modifier.size(24.dp))
            }

            state.code.isNotEmpty() -> Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                state.qr?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(200.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.height(14.dp))
                }
                Text(
                    // Пробел посередине: шесть цифр подряд труднее
                    // продиктовать и набрать.
                    text = state.code.chunked(3).joinToString(" "),
                    color = Brand.onDark,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Light,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.link_code_expires_in,
                        state.secondsLeft / 60,
                        state.secondsLeft % 60,
                    ),
                    color = Brand.onDarkMuted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(4.dp))
                // Опрос идёт сам: без этой строки непонятно, чего ждать.
                Text(
                    text = stringResource(R.string.link_waiting),
                    color = Brand.accent,
                    fontSize = 12.sp,
                )
            }

            state.issueError -> Text(
                text = stringResource(R.string.link_issue_error),
                color = Brand.onDarkMuted,
                fontSize = 13.sp,
            )
        }

        if (!state.issuing) {
            Spacer(Modifier.height(14.dp))
            PrimaryButton(
                text = stringResource(
                    if (state.code.isEmpty()) R.string.link_get_code
                    else R.string.link_get_code_again
                ),
                onClick = onIssue,
            )
        }
    }
}

/**
 * Половина «впустить»: её открывают там, где подписка уже есть.
 *
 * Камера — основной путь, цифры рядом на случай, когда снять экран
 * нечем: другое устройство далеко или это вовсе компьютер.
 */
@Composable
private fun GrantCard(
    state: LinkViewModel.UiState,
    draft: String,
    onDraftChange: (String) -> Unit,
    onScanClick: () -> Unit,
    onSubmit: () -> Unit,
) {
    Card {
        Text(
            text = stringResource(R.string.link_enter_title),
            color = Brand.onDark,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.link_enter_hint),
            color = Brand.onDarkMuted,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(14.dp))

        PrimaryButton(
            text = stringResource(R.string.link_scan_action),
            enabled = !state.granting,
            onClick = onScanClick,
        )

        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.link_enter_manual),
            color = Brand.onDarkMuted,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("000000", color = Brand.onDarkMuted) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Brand.onDark,
                unfocusedTextColor = Brand.onDark,
                focusedBorderColor = Brand.accent,
                unfocusedBorderColor = Brand.divider,
                cursorColor = Brand.accent,
            ),
        )

        val notice = when {
            state.offline -> R.string.backend_network_error
            state.noSubscription -> R.string.link_no_subscription
            state.grantError -> R.string.link_bad_code
            state.granted -> R.string.link_granted
            else -> null
        }
        notice?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(it),
                color = if (state.granted) Brand.accent else Brand.onDarkMuted,
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(14.dp))
        if (state.granting) {
            Box(Modifier.fillMaxWidth(), Alignment.Center) {
                CircularProgressIndicator(color = Brand.accent, modifier = Modifier.size(24.dp))
            }
        } else {
            PrimaryButton(
                text = stringResource(R.string.link_enter_action),
                enabled = draft.length == LinkViewModel.CODE_LENGTH,
                onClick = onSubmit,
            )
        }
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brand.card)
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(if (enabled) Brand.accentMuted else Brand.surface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) Brand.accent else Brand.onDarkMuted,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}
