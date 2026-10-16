package com.v2ray.ang.ui.renew

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MobileBackend
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Продление подписки.
 *
 * Весь экран приезжает одним ответом бэкенда: срок, трафик, тарифы и
 * судьба последнего чека. Панель приложение не трогает — продлевает тот,
 * кто чек подтвердит, а здесь только показываем, что происходит.
 */
class RenewViewModel(application: Application) : BaseViewModel(application) {

    /** Почему не вышло. Разделено потому, что чинится это по-разному. */
    enum class Failure { NONE, OFFLINE, TOO_BIG, UNREADABLE }

    data class UiState(
        /** Первая загрузка: показываем ожидание, а не пустой экран. */
        val loading: Boolean = true,
        /** До бэкенда не достучались. */
        val offline: Boolean = false,
        val state: MobileBackend.RenewState? = null,
        /** Выбран тариф — показываем экран оплаты. */
        val chosen: MobileBackend.Tier? = null,
        val sending: Boolean = false,
        /** Что сказать про последнюю неудачу: своим текстом или чужим. */
        val failure: Failure = Failure.NONE,
        val failureText: String = "",
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    fun load() {
        viewModelScope.launch {
            val answer = MobileBackend.renewState(getApplication())
            _uiState.update {
                if (answer == null) {
                    it.copy(loading = false, offline = true)
                } else {
                    it.copy(state = answer, loading = false, offline = false)
                }
            }
        }
    }

    fun pickTier(tier: MobileBackend.Tier) {
        _uiState.update { it.copy(chosen = tier, failure = Failure.NONE, failureText = "") }
    }

    fun backToTiers() {
        _uiState.update { it.copy(chosen = null) }
    }

    fun dismissFailure() {
        _uiState.update { it.copy(failure = Failure.NONE, failureText = "") }
    }

    /**
     * Отправляет выбранный файл чеком.
     *
     * Читаем его сами и шлём содержимым: приложение получает от системы
     * ссылку на файл, а не путь, и жить она может ровно до конца этого
     * экрана — отложить отправку «на потом» по ней нельзя.
     */
    fun sendReceipt(uri: Uri) {
        val tier = _uiState.value.chosen ?: return
        if (_uiState.value.sending) return

        _uiState.update { it.copy(sending = true, failure = Failure.NONE, failureText = "") }
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { readFile(uri) }

            if (file == null) {
                _uiState.update {
                    it.copy(sending = false, failure = Failure.UNREADABLE)
                }
                return@launch
            }
            if (file.bytes.size > MobileBackend.RECEIPT_LIMIT_BYTES) {
                _uiState.update { it.copy(sending = false, failure = Failure.TOO_BIG) }
                return@launch
            }

            val (request, refusal) = MobileBackend.sendReceipt(
                getApplication(), tier.index, file.bytes, file.name
            )

            when {
                // Заявка завелась: показываем её вместо списка тарифов и
                // перечитываем экран — заодно подтянется свежий срок.
                request != null -> {
                    _uiState.update { current ->
                        current.copy(
                            sending = false,
                            chosen = null,
                            state = current.state?.copy(request = request),
                        )
                    }
                    load()
                }

                // Бэкенд отказал и объяснил, почему, — его слова и покажем.
                refusal != null -> _uiState.update {
                    it.copy(sending = false, failure = Failure.OFFLINE, failureText = refusal)
                }

                else -> _uiState.update { it.copy(sending = false, failure = Failure.OFFLINE) }
            }
        }
    }

    private class PickedFile(val bytes: ByteArray, val name: String)

    private fun readFile(uri: Uri): PickedFile? = try {
        val resolver = getApplication<Application>().contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }

        if (bytes == null || bytes.isEmpty()) {
            null
        } else {
            PickedFile(bytes, displayName(uri))
        }
    } catch (e: Exception) {
        LogUtil.w(AppConfig.TAG, "Чек не прочитался: ${e.message}")
        null
    }

    /** Имя файла, как его показывает система. Пустое — придумает бэкенд. */
    private fun displayName(uri: Uri): String = runCatching {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull().orEmpty()

    /**
     * Опрос, пока экран открыт и заявка не рассмотрена.
     *
     * Сообщить нам о решении бэкенд не умеет, а человек в этот момент
     * как раз смотрит на экран и ждёт. Спрашиваем редко: решение
     * принимает человек на той стороне.
     */
    fun startPolling() {
        if (pollJob?.isActive == true) return

        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (_uiState.value.state?.request?.isOpen != true) continue

                val answer = MobileBackend.renewState(getApplication()) ?: continue
                _uiState.update { it.copy(state = answer, offline = false) }
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 10_000L
    }
}
