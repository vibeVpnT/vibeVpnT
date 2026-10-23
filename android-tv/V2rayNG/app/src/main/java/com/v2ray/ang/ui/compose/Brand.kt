package com.v2ray.ang.ui.compose

import androidx.compose.ui.graphics.Color
import com.v2ray.ang.BuildConfig

/**
 * Единственная точка, через которую бренд попадает в интерфейс.
 *
 * Сами значения лежат в brand.properties и приезжают сюда через BuildConfig,
 * поэтому в коде экранов нет ни одного зашитого цвета или адреса: чтобы
 * выпустить приложение под другим именем, правится только тот файл.
 */
object Brand {
    val name: String = BuildConfig.BRAND_NAME

    val accent = Color(BuildConfig.BRAND_ACCENT.toInt())
    val background = Color(BuildConfig.BRAND_BACKGROUND.toInt())
    val surface = Color(BuildConfig.BRAND_SURFACE.toInt())
    val card = Color(BuildConfig.BRAND_CARD.toInt())

    /** Акцент вполсилы: подложка выбранной вкладки, обводка активной плитки. */
    val accentMuted = accent.copy(alpha = 0.16f)

    /** Текст: основной и приглушённый — для подписей под заголовками. */
    val onDark = Color(0xFFFFFFFF)
    val onDarkMuted = Color(0xFFA5A5A5)

    val divider = Color(0x14FFFFFF)

    /** Подложка выпадающих меню — чуть светлее фона, но темнее панелей. */
    val menu = Color(0xFF201F25)

    /** Серая плашка типа протокола слева от названия сервера. */
    val badge = Color(0xFF272727)

    /** Задержка до сервера: зелёная, как в V2Box. */
    val ping = Color(0xFF00B994)

    // Состояние подключения показывается не отдельным цветом, а тем же
    // акцентом против приглушённого серого.
    val connected = accent
    val disconnected = Color(0xFF5A5A5A)

    /**
     * Бэкенд приложения: при запуске оно представляется туда своим
     * Android ID и получает готовый адрес подписки. Пусто — приложение
     * ведёт себя как обычный клиент, подписку добавляют вручную.
     */
    val backendUrl: String = BuildConfig.BRAND_BACKEND_URL

    /** Ссылки из brand.properties; пустая строка — кнопку не показываем. */
    val supportUrl: String = BuildConfig.BRAND_SUPPORT_URL
    val siteUrl: String = BuildConfig.BRAND_SITE_URL
    val subscriptionUrl: String = BuildConfig.BRAND_SUBSCRIPTION_URL
    val subscriptionName: String = BuildConfig.BRAND_SUBSCRIPTION_NAME
}
