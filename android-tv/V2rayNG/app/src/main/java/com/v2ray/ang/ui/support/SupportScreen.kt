package com.v2ray.ang.ui.support

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.handler.MobileBackend
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.Brand
import kotlinx.coroutines.flow.distinctUntilChanged

/** Сколько знаков принимает бэкенд — столько же не даём превысить здесь. */
private const val MAX_LENGTH = 2000

@Composable
fun SupportScreen(viewModel: SupportViewModel, onBackClick: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var draft by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.load()
        viewModel.startPolling()
    }

    // Новое сообщение — прокручиваем вниз, как в любом мессенджере.
    LaunchedEffect(state.messages.lastOrNull()?.id) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    // Доскроллили до верха — подтягиваем страницу истории.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index -> if (index == 0) viewModel.loadOlder() }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.title_support_chat),
                onBackClick = onBackClick,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
        ) {
            if (state.offline) {
                Notice(stringResource(R.string.support_offline))
            }

            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.loading -> CircularProgressIndicator(
                        color = Brand.accent,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    state.messages.isEmpty() -> Text(
                        text = stringResource(R.string.support_empty),
                        color = Brand.onDarkMuted,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 32.dp),
                    )

                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp, vertical = 12.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.loadingOlder) {
                            item("older") {
                                Box(Modifier.fillMaxWidth(), Alignment.Center) {
                                    CircularProgressIndicator(
                                        color = Brand.accent,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                        items(state.messages, key = { it.id }) { message ->
                            Bubble(message)
                        }
                    }
                }
            }

            Composer(
                draft = draft,
                sending = state.sending,
                onDraftChange = { if (it.length <= MAX_LENGTH) draft = it },
                onSend = {
                    viewModel.send(draft)
                    draft = ""
                },
            )
        }
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text = text,
        color = Brand.onDarkMuted,
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(Brand.card)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Своё сообщение прижато вправо и в цвет акцента, ответ поддержки — влево. */
@Composable
private fun Bubble(message: MobileBackend.SupportMessage) {
    val fromSupport = message.fromSupport

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromSupport) Arrangement.Start else Arrangement.End,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 14.dp,
                        topEnd = 14.dp,
                        bottomStart = if (fromSupport) 4.dp else 14.dp,
                        bottomEnd = if (fromSupport) 14.dp else 4.dp,
                    )
                )
                .background(if (fromSupport) Brand.card else Brand.accentMuted)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (fromSupport) {
                // Подпись одна на все ответы и своя на каждом языке:
                // кто именно из поддержки на смене, человеку неважно,
                // а имя из Telegram в приложении и вовсе лишнее.
                Text(
                    text = stringResource(R.string.title_support_chat),
                    color = Brand.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(text = message.text, color = Brand.onDark, fontSize = 15.sp)
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    sending: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val canSend = draft.isNotBlank() && !sending

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brand.surface)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(stringResource(R.string.support_hint), color = Brand.onDarkMuted)
            },
            maxLines = 5,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Brand.onDark,
                unfocusedTextColor = Brand.onDark,
                focusedBorderColor = Brand.accent,
                unfocusedBorderColor = Brand.divider,
                cursorColor = Brand.accent,
            ),
        )

        IconButton(onClick = onSend, enabled = canSend) {
            Icon(
                painter = painterResource(R.drawable.ic_send_24dp),
                contentDescription = stringResource(R.string.support_send),
                tint = if (canSend) Brand.accent else Brand.onDarkMuted,
            )
        }
    }
}
