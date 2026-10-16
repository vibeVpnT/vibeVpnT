package com.v2ray.ang.ui.link

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.v2ray.ang.ui.base.HelperBaseComponentActivity

/**
 * Вход на другом устройстве: показать свой код или впустить чужой.
 *
 * Код показывает тот, кто входит; сканирует тот, у кого подписка есть.
 * Открывается и по ссылке из QR (<схема>://join?c=…) — так приходит код,
 * снятый сторонней камерой, и он сразу уходит в работу.
 */
class LinkActivity : HelperBaseComponentActivity() {
    private val viewModel: LinkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Код из QR: экран открылся уже с ним, спрашивать нечего.
        intent.getStringExtra(EXTRA_CODE)?.let(viewModel::grantFromLink)
    }

    @Composable
    override fun ScreenContent() {
        LinkScreen(
            viewModel = viewModel,
            onBackClick = { finish() },
            onEntered = {
                setResult(RESULT_OK, Intent())
                finish()
            },
            // Сканер заводится в onCreate базового класса, а не здесь:
            // регистрировать запуск чужого экрана можно только до того,
            // как активити показалась. Сделанное по месту, при нажатии,
            // роняло приложение — ровно на кнопке «Отсканировать».
            onScanClick = {
                launchQRCodeScanner { scanned -> scanned?.let { viewModel.grantCode(codeOf(it)) } }
            },
        )
    }

    /**
     * Цифры кода из того, что увидела камера.
     *
     * В QR нашего приложения лежит ссылка, но человек может навести
     * камеру и на просто напечатанные шесть цифр — разбираем оба случая.
     */
    private fun codeOf(scanned: String): String {
        val fromLink = runCatching { Uri.parse(scanned).getQueryParameter("c") }.getOrNull()
        return (fromLink ?: scanned).filter(Char::isDigit)
    }

    companion object {
        const val EXTRA_CODE = "link_code"
    }
}
