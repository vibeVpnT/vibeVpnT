package com.v2ray.ang.dto

/**
 * Разбор заголовка subscription-userinfo.
 *
 * Выглядит он так:
 *   upload=0; download=598765432100; total=0; expire=1793404800
 *
 * total=0 означает «без лимита», expire=0 — «бессрочно»; так принято у
 * панелей, и отличать это от «ноль байт осталось» обязательно.
 */
data class SubscriptionUserInfo(
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expire: Long = 0,
) {
    val used: Long get() = upload + download
    val unlimited: Boolean get() = total <= 0
    val neverExpires: Boolean get() = expire <= 0

    companion object {
        fun parse(header: String?): SubscriptionUserInfo? {
            if (header.isNullOrBlank()) return null

            val values = header.split(";")
                .mapNotNull { part ->
                    val pair = part.split("=", limit = 2)
                    if (pair.size != 2) null
                    else pair[0].trim().lowercase() to (pair[1].trim().toLongOrNull() ?: return@mapNotNull null)
                }
                .toMap()

            if (values.isEmpty()) return null

            return SubscriptionUserInfo(
                upload = values["upload"] ?: 0,
                download = values["download"] ?: 0,
                total = values["total"] ?: 0,
                expire = values["expire"] ?: 0,
            )
        }
    }
}
