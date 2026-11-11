package com.v2ray.ang.ui.main

import com.v2ray.ang.handler.MobileBackend

/**
 * Что главный экран навязывает сам, а что оставляет на выбор.
 *
 * Вынесено из MainViewModel чистыми функциями: решение зависит только от
 * ответа бэкенда и флага «пропустить навсегда», и его можно проверить
 * без Android.
 */
internal object Onboarding {

    /** Открыть экран входа и регистрации: аккаунта нет и не отказывались. */
    fun showWelcome(status: MobileBackend.Status?, skipped: Boolean): Boolean =
        status == MobileBackend.Status.NEEDS_ACCOUNT && !skipped

    /** Предложить триал окном: бэкенд предлагает и не отказывались. */
    fun showTrialOffer(status: MobileBackend.Status?, skipped: Boolean): Boolean =
        status == MobileBackend.Status.OFFER && !skipped

    /**
     * «Подписка» и «Поддержка» в меню. Без аккаунта бэкенд флагов не
     * присылает, но пункты нужны и тогда: экран сам скажет, что для них
     * нужен вход, — прятать их значило бы запирать человека.
     */
    fun showAccountItem(enabledByBackend: Boolean, status: MobileBackend.Status?): Boolean =
        enabledByBackend || status == MobileBackend.Status.NEEDS_ACCOUNT
}
