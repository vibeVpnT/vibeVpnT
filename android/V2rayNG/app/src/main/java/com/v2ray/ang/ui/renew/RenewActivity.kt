package com.v2ray.ang.ui.renew

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.v2ray.ang.ui.base.HelperBaseComponentActivity

/**
 * Подписка и продление.
 *
 * Наследуемся от HelperBaseComponentActivity, а не заводим выбор файла по
 * месту: запуск чужого экрана регистрируется только до того, как
 * активити показалась, и сделанное по нажатию кнопки роняет приложение.
 */
class RenewActivity : HelperBaseComponentActivity() {
    private val viewModel: RenewViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        RenewScreen(
            viewModel = viewModel,
            onBackClick = { finish() },
            // Чеком может быть что угодно: снимок экрана, фотография,
            // pdf из банка. Поэтому тип файла не ограничиваем.
            onPickReceipt = {
                launchFileChooser("*/*") { uri -> uri?.let(viewModel::sendReceipt) }
            },
        )
    }

    override fun onStart() {
        super.onStart()
        viewModel.startPolling()
    }

    override fun onStop() {
        // Экран ушёл с глаз — спрашивать бэкенд незачем.
        viewModel.stopPolling()
        super.onStop()
    }
}
