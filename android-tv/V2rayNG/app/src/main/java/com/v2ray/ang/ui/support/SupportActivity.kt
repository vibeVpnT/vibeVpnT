package com.v2ray.ang.ui.support

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.v2ray.ang.ui.base.BaseComponentActivity

/**
 * Чат с поддержкой.
 *
 * Переписка идёт через наш бэкенд: токенов бота в приложении нет, а в
 * Telegram сообщения относит сервер.
 */
class SupportActivity : BaseComponentActivity() {
    private val viewModel: SupportViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        SupportScreen(viewModel = viewModel, onBackClick = { finish() })
    }

    override fun onStop() {
        // Экран ушёл с глаз — опрашивать новое незачем.
        viewModel.stopPolling()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        viewModel.startPolling()
    }
}
