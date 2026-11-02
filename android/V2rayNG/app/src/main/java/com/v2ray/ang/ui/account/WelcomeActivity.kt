package com.v2ray.ang.ui.account

import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.link.LinkActivity

/**
 * Вход и регистрация при запуске без аккаунта.
 *
 * Состояние то же, что у экрана «Аккаунт» (номер, вход, пробный период),
 * поэтому и ViewModel общая; отличается только раскладка.
 */
class WelcomeActivity : BaseComponentActivity() {
    private val viewModel: AccountViewModel by viewModels()

    // Регистрируется до показа активити: сделанное по нажатию роняет
    // приложение (см. LinkActivity).
    private val linkLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.refresh()
        }

    @Composable
    override fun ScreenContent() {
        WelcomeScreen(
            viewModel = viewModel,
            onLinkClick = { linkLauncher.launch(Intent(this, LinkActivity::class.java)) },
            onDone = { finish() },
            onLater = { finish() },
        )
    }
}
