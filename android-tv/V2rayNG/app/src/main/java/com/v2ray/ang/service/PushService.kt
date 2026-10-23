package com.v2ray.ang.service

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.handler.MobileBackend
import com.v2ray.ang.handler.PushManager
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Приём push-уведомлений.
 *
 * Приходят они редко и только по делу: решение по оплате. Поэтому
 * здесь две вещи — показать уведомление и сходить за свежей подпиской.
 *
 * Второе важнее первого: человек, открыв приложение после уведомления,
 * должен увидеть новый срок, а не прежний. Поэтому за подпиской идём
 * сразу, не дожидаясь, пока он откроет окно.
 */
class PushService : FirebaseMessagingService() {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onNewToken(token: String) {
        // Адрес меняется по своим причинам: переустановка, очистка
        // данных, долгое молчание. Каждый раз сообщаем бэкенду новый.
        scope.launch { PushManager.register(applicationContext, token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title
            ?: message.data["title"]
            ?: getString(R.string.app_name)
        val body = message.notification?.body ?: message.data["body"] ?: return

        LogUtil.i(AppConfig.TAG, "Push: $title")

        NotificationHelper.notify(
            NotificationChannelType.SUBSCRIPTION_UPDATE,
            applicationContext,
            title,
            body,
        )

        // Подписка могла измениться — перечитываем её молча, чтобы
        // открытое следом приложение показало новый срок сразу.
        scope.launch {
            try {
                MobileBackend.provision(applicationContext, claim = false)
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "Push: подписка не обновилась: ${e.message}")
            }
        }
    }
}
