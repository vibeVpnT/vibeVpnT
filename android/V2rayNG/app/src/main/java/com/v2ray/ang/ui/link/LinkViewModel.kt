package com.v2ray.ang.ui.link

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.handler.MobileBackend
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.QRCodeDecoder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Вход на другом устройстве.
 *
 * Две стороны одного действия: на устройстве без подписки показывают
 * код, на устройстве с подпиской его сканируют. Сканирует тот, кто
 * делится, — так же, как в Steam и Telegram.
 *
 * Это вход в аккаунт впустившего, а не второй ключ: показавший код
 * получает свою сессию в том же аккаунте и ту же подписку.
 */
class LinkViewModel(application: Application) : BaseViewModel(application) {

    data class UiState(
        /** Выпущенный код: шесть цифр. Пусто — ещё не просили. */
        val code: String = "",
        val qr: Bitmap? = null,
        /** Сколько секунд коду осталось жить. */
        val secondsLeft: Int = 0,
        val issuing: Boolean = false,
        /** Код выпустить не удалось: бэкенд не ответил. */
        val issueError: Boolean = false,

        /** Идёт передача доступа по отсканированному коду. */
        val granting: Boolean = false,
        val grantError: Boolean = false,
        /** Делиться нечем: на этом устройстве нет подписки. */
        val noSubscription: Boolean = false,
        /** Доступ отдан — показываем это на устройстве, которое делилось. */
        val granted: Boolean = false,

        /** Нас впустили: подписка приехала, экран можно закрывать. */
        val entered: Boolean = false,

        val offline: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var countdown: Job? = null
    private var waiting: Job? = null

    /** Просит код и показывает его: этим устройством хотят войти. */
    fun issueCode() {
        if (_uiState.value.issuing) return

        _uiState.update { it.copy(issuing = true, issueError = false, offline = false) }
        viewModelScope.launch {
            val link = MobileBackend.requestJoinCode()

            if (link == null) {
                _uiState.update { it.copy(issuing = false, issueError = true) }
                return@launch
            }

            _uiState.update {
                it.copy(
                    issuing = false,
                    code = link.code,
                    qr = QRCodeDecoder.createQRCode(MobileBackend.joinQrPayload(link.code), 600),
                    secondsLeft = link.ttlSeconds,
                )
            }
            startCountdown()
            startWaiting(link)
        }
    }

    /** Код живёт считанные минуты — показываем, сколько осталось. */
    private fun startCountdown() {
        countdown?.cancel()
        countdown = viewModelScope.launch {
            while (isActive && _uiState.value.secondsLeft > 0) {
                delay(1000)
                _uiState.update { it.copy(secondsLeft = (it.secondsLeft - 1).coerceAtLeast(0)) }
            }
            // Истёкший код показывать нельзя: его уже не примут.
            waiting?.cancel()
            _uiState.update { it.copy(code = "", qr = null) }
        }
    }

    /**
     * Ждём, когда код отсканируют.
     *
     * Иначе человеку пришлось бы догадаться выйти и зайти: сам по себе
     * подписку он не получит, а уведомить его нам нечем.
     */
    private fun startWaiting(link: MobileBackend.LinkCode) {
        waiting?.cancel()
        waiting = viewModelScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val result = MobileBackend.pollJoinStatus(getApplication(), link)
                if (result.status == MobileBackend.Status.ACTIVE) {
                    _uiState.update { it.copy(entered = true) }
                    return@launch
                }
            }
        }
    }

    /**
     * Отдаёт свою подписку по чужому коду.
     *
     * Код приходит либо из камеры, либо набран руками — для бэкенда это
     * одно и то же.
     */
    fun grantCode(code: String) {
        val digits = code.filter { it.isDigit() }
        if (digits.length != CODE_LENGTH || _uiState.value.granting) return

        _uiState.update {
            it.copy(
                granting = true,
                grantError = false,
                noSubscription = false,
                offline = false,
                granted = false,
            )
        }
        viewModelScope.launch {
            val result = MobileBackend.grantLinkCode(getApplication(), digits)
            _uiState.update {
                when (result.status) {
                    MobileBackend.Status.ACTIVE ->
                        it.copy(granting = false, granted = true)

                    MobileBackend.Status.NETWORK_ERROR ->
                        it.copy(granting = false, offline = true)

                    MobileBackend.Status.UNAVAILABLE ->
                        it.copy(granting = false, noSubscription = true)

                    else -> it.copy(granting = false, grantError = true)
                }
            }
        }
    }

    /**
     * Код из QR, снятого чужой камерой.
     *
     * В ссылке может приехать что угодно — берём только цифры и только
     * если их ровно столько, сколько нужно.
     */
    fun grantFromLink(code: String) = grantCode(code)

    override fun onCleared() {
        countdown?.cancel()
        waiting?.cancel()
        super.onCleared()
    }

    companion object {
        const val CODE_LENGTH = 6

        /** Как часто спрашиваем, не впустили ли нас. */
        private const val POLL_INTERVAL_MS = 3_000L
    }
}
