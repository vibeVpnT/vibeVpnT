package com.v2ray.ang.ui.link

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.HelperBaseComponentActivity
import com.v2ray.ang.ui.compose.Brand
import kotlinx.coroutines.delay

/**
 * Вход на телевизоре — так же, как в настольной версии.
 *
 * Своей подписки телевизор не получает: триал раздаётся телефонам, а
 * телевизор входит по коду с телефона, где подписка уже есть. Экран
 * открывается сам, когда подписки нет, сразу показывает QR и ждёт, пока
 * его отсканируют. Код живёт минуты — истёк, просим новый сами: пультом
 * лишний раз нажимать незачем.
 *
 * Выдача кода и ожидание — те же, что у «Войти на этом устройстве»
 * (LinkViewModel): меняется только экран, под телевизор.
 */
class TvLoginActivity : HelperBaseComponentActivity() {
    private val viewModel: LinkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.issueCode()
    }

    @Composable
    override fun ScreenContent() {
        val state by viewModel.uiState.collectAsStateWithLifecycle()

        LaunchedEffect(state.entered) {
            if (state.entered) {
                setResult(RESULT_OK, Intent())
                finish()
            }
        }

        // Код истёк — сразу новый. Не выдался — пробуем снова через
        // десять секунд: телевизор мог включиться раньше, чем сеть.
        LaunchedEffect(state.code, state.issuing, state.issueError) {
            if (state.issuing || state.code.isNotEmpty()) return@LaunchedEffect
            if (state.issueError) delay(RETRY_MS)
            viewModel.issueCode()
        }

        TvLoginScreen(state)
    }

    private companion object {
        const val RETRY_MS = 10_000L
    }
}

@Composable
private fun TvLoginScreen(state: LinkViewModel.UiState) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Brand.background)
            .padding(horizontal = 72.dp, vertical = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        // QR — на белом: на тёмном фоне камера телефона ловит его хуже.
        Box(
            modifier = Modifier
                .size(320.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            val qr = state.qr
            if (qr != null && state.code.isNotEmpty()) {
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(292.dp),
                )
            } else {
                CircularProgressIndicator(color = Brand.background)
            }
        }

        Spacer(Modifier.width(56.dp))

        Column(modifier = Modifier.width(520.dp)) {
            Text(
                text = stringResource(R.string.tv_login_title),
                color = Brand.onDark,
                fontSize = 34.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.tv_login_hint),
                color = Brand.onDarkMuted,
                fontSize = 19.sp,
                lineHeight = 27.sp,
            )
            Spacer(Modifier.height(28.dp))

            when {
                state.code.isNotEmpty() -> {
                    // Цифрами — если камере неудобно: их можно набрать
                    // на телефоне руками.
                    Text(
                        text = state.code.chunked(3).joinToString(" "),
                        color = Brand.onDark,
                        fontSize = 52.sp,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 4.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.link_code_expires_in,
                            state.secondsLeft / 60,
                            state.secondsLeft % 60,
                        ),
                        color = Brand.onDarkMuted,
                        fontSize = 16.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.link_waiting),
                        color = Brand.accent,
                        fontSize = 16.sp,
                    )
                }

                state.issueError -> Text(
                    text = stringResource(R.string.tv_login_error),
                    color = Brand.onDarkMuted,
                    fontSize = 17.sp,
                )
            }
        }
    }
}
