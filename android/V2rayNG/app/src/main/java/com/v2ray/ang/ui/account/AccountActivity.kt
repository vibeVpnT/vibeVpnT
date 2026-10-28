package com.v2ray.ang.ui.account

import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.link.LinkActivity

/** Аккаунт: номер, вход по номеру или по коду, выход. */
class AccountActivity : BaseComponentActivity() {
    private val viewModel: AccountViewModel by viewModels()

    // Регистрируется до показа активити: сделанное по нажатию роняет
    // приложение (см. LinkActivity).
    private val linkLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.refresh()
        }

    @Composable
    override fun ScreenContent() {
        AccountScreen(
            viewModel = viewModel,
            onBackClick = { finish() },
            onLinkClick = { linkLauncher.launch(Intent(this, LinkActivity::class.java)) },
        )
    }
}
