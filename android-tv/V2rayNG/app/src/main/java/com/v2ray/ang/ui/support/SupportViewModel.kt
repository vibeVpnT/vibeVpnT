package com.v2ray.ang.ui.support

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.handler.MobileBackend
import com.v2ray.ang.ui.base.BaseViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Переписка с поддержкой.
 *
 * Токенов бота в приложении нет: всё идёт через наш бэкенд, он же
 * относит сообщения в Telegram. Здесь — только загрузка, отправка и
 * опрос новых сообщений, пока экран открыт.
 */
class SupportViewModel(application: Application) : BaseViewModel(application) {

    data class UiState(
        val messages: List<MobileBackend.SupportMessage> = emptyList(),
        /** Первая загрузка: показываем ожидание вместо пустого экрана. */
        val loading: Boolean = true,
        /** Подгрузка истории выше. */
        val loadingOlder: Boolean = false,
        /** Выше есть ещё — прокрутка вверх подтянет следующую страницу. */
        val hasMore: Boolean = false,
        val sending: Boolean = false,
        /** До бэкенда не достучались: показываем это, а не пустой чат. */
        val offline: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    /** Открытие экрана: хвост переписки, как в любом мессенджере. */
    fun load() {
        viewModelScope.launch {
            val page = MobileBackend.supportMessages(getApplication())
            _uiState.update {
                if (page == null) {
                    it.copy(loading = false, offline = true)
                } else {
                    it.copy(
                        messages = page.messages,
                        hasMore = page.hasMore,
                        loading = false,
                        offline = false,
                    )
                }
            }
        }
    }

    /** Страница выше — вызывается, когда список доскроллили до верха. */
    fun loadOlder() {
        val state = _uiState.value
        val oldest = state.messages.firstOrNull() ?: return
        if (state.loadingOlder || !state.hasMore) return

        _uiState.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            val page = MobileBackend.supportMessages(getApplication(), before = oldest.id)
            _uiState.update { current ->
                if (page == null) {
                    current.copy(loadingOlder = false)
                } else {
                    current.copy(
                        // Страница кладётся ПЕРЕД уже показанным, порядок
                        // внутри неё уже от старых к новым.
                        messages = page.messages + current.messages,
                        hasMore = page.messages.isNotEmpty(),
                        loadingOlder = false,
                    )
                }
            }
        }
    }

    fun send(text: String) {
        val body = text.trim()
        if (body.isEmpty() || _uiState.value.sending) return

        _uiState.update { it.copy(sending = true) }
        viewModelScope.launch {
            val sent = MobileBackend.sendSupport(getApplication(), body)
            _uiState.update { current ->
                if (sent == null) {
                    current.copy(sending = false, offline = true)
                } else {
                    current.copy(
                        messages = current.messages + sent,
                        sending = false,
                        offline = false,
                    )
                }
            }
        }
    }

    /**
     * Опрос новых сообщений, пока экран на виду.
     *
     * Спрашиваем только то, что появилось после последнего известного —
     * тянуть всю переписку каждые несколько секунд незачем.
     */
    fun startPolling() {
        if (pollJob?.isActive == true) return

        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)

                val lastId = _uiState.value.messages.lastOrNull()?.id ?: continue
                val page = MobileBackend.supportMessages(getApplication(), after = lastId)
                    ?: continue

                if (page.messages.isNotEmpty()) {
                    _uiState.update { it.copy(messages = it.messages + page.messages) }
                }
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
        // Поддержка отвечает не за секунды, так что чаще смысла нет.
        const val POLL_INTERVAL_MS = 4000L
    }
}
