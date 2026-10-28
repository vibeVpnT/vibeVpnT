package com.v2ray.ang.ui.account

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MobileBackend
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Аккаунт: номер, вход по номеру, выход.
 *
 * Номер — единственное, чем человек входит на любом устройстве и в
 * личном кабинете. Здесь его видно, здесь же входят в другой аккаунт.
 */
class AccountViewModel(application: Application) : BaseViewModel(application) {

    /** Почему не вошли. */
    enum class LoginError { INVALID, NOT_FOUND, TOO_MANY_ATTEMPTS, OFFLINE }

    data class UiState(
        /** Номер аккаунта, в который вошло устройство. Пусто — не вошло. */
        val number: String = "",
        /** Идёт вход, создание или выход. */
        val busy: Boolean = false,
        val loginError: LoginError? = null,
        /** Пробный период не выдан: бэкенд не ответил. */
        val createError: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState(number = MobileBackend.accountNumber))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Вход по номеру. Опечатку ловим здесь, не тратя попыток на сервере. */
    fun login(raw: String) {
        if (_uiState.value.busy) return
        if (!MobileBackend.isValidAccountNumber(raw)) {
            _uiState.update { it.copy(loginError = LoginError.INVALID) }
            return
        }

        _uiState.update { it.copy(busy = true, loginError = null, createError = false) }
        viewModelScope.launch {
            val outcome = MobileBackend.login(raw.filter(Char::isDigit))
            val error = when (outcome) {
                MobileBackend.LoginOutcome.OK -> null
                MobileBackend.LoginOutcome.NOT_FOUND -> LoginError.NOT_FOUND
                MobileBackend.LoginOutcome.TOO_MANY_ATTEMPTS -> LoginError.TOO_MANY_ATTEMPTS
                MobileBackend.LoginOutcome.NETWORK_ERROR -> LoginError.OFFLINE
            }
            _uiState.update {
                it.copy(busy = false, loginError = error, number = MobileBackend.accountNumber)
            }
        }
    }

    /** Нет аккаунта: завести его вместе с пробным периодом. */
    fun createWithTrial() {
        if (_uiState.value.busy) return

        _uiState.update { it.copy(busy = true, loginError = null, createError = false) }
        viewModelScope.launch {
            val result = try {
                MobileBackend.provision(getApplication(), claim = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Аккаунт с пробным периодом не создан", error)
                null
            }
            val failed = result == null || result.status == MobileBackend.Status.NETWORK_ERROR
            _uiState.update {
                it.copy(busy = false, createError = failed, number = MobileBackend.accountNumber)
            }
        }
    }

    fun logout() {
        if (_uiState.value.busy) return

        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            MobileBackend.logout()
            _uiState.update { it.copy(busy = false, number = "", loginError = null) }
        }
    }

    /** Вернулись с экрана входа по коду: там могли войти. */
    fun refresh() {
        _uiState.update { it.copy(number = MobileBackend.accountNumber) }
    }
}
