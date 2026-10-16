package com.v2ray.ang.handler

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Push-уведомления: адрес этого телефона и его передача бэкенду.
 *
 * Зачем они вообще: пока приложение открыто, оно спрашивает новости
 * само. А если закрыто, узнать о решении по оплате неоткуда — человек
 * возвращается проверять вручную. Теперь сервер сам говорит: «продлено».
 *
 * Работает не везде, и это нормально. На телефонах без сервисов Google
 * (свежие Huawei) Firebase не заводится вовсе — тогда адреса просто не
 * будет, уведомлений тоже, а всё остальное работает как работало.
 */
object PushManager {

    /**
     * Сообщает бэкенду адрес этого телефона.
     *
     * Зовётся при запуске и всякий раз, когда Firebase адрес меняет —
     * а меняет он его по своим причинам: переустановка, очистка данных,
     * долгое молчание.
     */
    suspend fun register(context: Context, token: String? = null) {
        if (!MobileBackend.isConfigured) return

        val address = token ?: currentToken() ?: return
        val sent = MobileBackend.registerPush(context, address)
        LogUtil.i(AppConfig.TAG, if (sent) "Push: адрес передан" else "Push: адрес не принят")
    }

    /** Текущий адрес. null — Firebase не настроен или не отозвался. */
    private suspend fun currentToken(): String? = withContext(Dispatchers.IO) {
        try {
            suspendCoroutine<String?> { continuation ->
                FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener {
                        LogUtil.w(AppConfig.TAG, "Push: адрес не выдан: ${it.message}")
                        continuation.resume(null)
                    }
            }
        } catch (e: Exception) {
            // Сюда приходим на телефонах без сервисов Google: сам вызов
            // бросает исключение ещё до всяких слушателей.
            LogUtil.w(AppConfig.TAG, "Push недоступны: ${e.message}")
            null
        }
    }
}
