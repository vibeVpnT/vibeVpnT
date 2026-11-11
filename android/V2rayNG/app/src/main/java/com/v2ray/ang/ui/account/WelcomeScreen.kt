package com.v2ray.ang.ui.account

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.handler.MobileBackend
import com.v2ray.ang.ui.compose.Brand

/**
 * Первый экран без аккаунта: завести новый или войти в свой.
 *
 * Отдельная страница, а не окно поверх главного: без аккаунта
 * приложению нечего показать, и выбор тут главный. «Пропустить» убирает
 * её навсегда: дальше всё доступно и без входа, а войти можно в «Аккаунте».
 */
@Composable
fun WelcomeScreen(
    viewModel: AccountViewModel,
    onLinkClick: () -> Unit,
    onDone: () -> Unit,
    onLater: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }

    // Вошли или завели аккаунт — дальше главный экран: он покажет
    // номер, который надо сохранить, и подтянет серверы.
    LaunchedEffect(state.number) {
        if (state.number.isNotEmpty()) onDone()
    }

    Scaffold(containerColor = Brand.background) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(40.dp))
            Text(
                text = stringResource(R.string.account_start_title, Brand.name),
                modifier = Modifier.fillMaxWidth(),
                color = Brand.onDark,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.account_start_text),
                modifier = Modifier.fillMaxWidth(),
                color = Brand.onDarkMuted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))

            Card {
                Title(stringResource(R.string.account_new_title))
                Hint(stringResource(R.string.account_new_hint))
                if (state.createError) {
                    Spacer(Modifier.height(8.dp))
                    Notice(stringResource(R.string.backend_network_error))
                }
                Spacer(Modifier.height(14.dp))
                PrimaryButton(
                    text = stringResource(R.string.account_welcome_trial),
                    enabled = !state.busy,
                    onClick = viewModel::createWithTrial,
                )
            }

            Spacer(Modifier.height(16.dp))
            LoginCard(
                loggedIn = false,
                state = state,
                draft = draft,
                onDraftChange = { value ->
                    draft = value.filter(Char::isDigit).take(MobileBackend.ACCOUNT_NUMBER_LENGTH)
                },
                onSubmit = { viewModel.login(draft) },
            )

            Spacer(Modifier.height(16.dp))
            Card {
                Title(stringResource(R.string.account_qr_title))
                Hint(stringResource(R.string.account_qr_hint))
                Spacer(Modifier.height(14.dp))
                PrimaryButton(
                    text = stringResource(R.string.account_qr_action),
                    enabled = !state.busy,
                    onClick = onLinkClick,
                )
            }

            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextButton(onClick = onLater, enabled = !state.busy) {
                    Text(stringResource(R.string.account_start_later), color = Brand.onDarkMuted)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
