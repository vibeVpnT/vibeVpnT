package com.v2ray.ang.dto

/**
 * Ответ на запрос подписки.
 *
 * Кроме самого списка серверов панель присылает заголовок
 * subscription-userinfo — из него видно потраченный трафик и срок.
 */
data class UrlContentResult(
    val body: String,
    val subscriptionUserInfo: String? = null,
)
