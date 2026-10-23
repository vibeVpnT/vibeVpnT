package com.v2ray.ang.ui.main

import com.v2ray.ang.handler.AppUpdater
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.LocateTarget
import com.v2ray.ang.handler.MobileBackend

/** По чему выстроен список серверов. */
enum class ServerSort { Ping, Name, Manual }

/** Что приложение предлагает человеку при первом запуске. */
data class TrialOffer(val days: Int, val gb: Int)

/** Locale-neutral state formatted only when it reaches the main UI. */
sealed interface MainStatus {
    data object Disconnected : MainStatus
    data object Connected : MainStatus
    data object Testing : MainStatus
    data class TestProgress(val progress: String) : MainStatus
    data class ConnectionTest(val result: ConnectionTestResult) : MainStatus
}

/**
 * Main UI state
 */
data class MainUiState(
    val groups: List<GroupMapItem> = emptyList(),
    val selectedGroupId: String = "",
    val selectedGuid: String? = null,
    val isRunning: Boolean = false,
    val isTesting: Boolean = false,
    val status: MainStatus = MainStatus.Disconnected,
    val locateTarget: LocateTarget? = null,
    val confirmRemove: Boolean = false,
    val doubleColumnDisplay: Boolean = false,
    val shareQRCodeBitmap: android.graphics.Bitmap? = null,
    /** Название выбранного сервера — его показывает главный экран. */
    val selectedRemarks: String = "",

    /** Что ответил наш бэкенд при запуске. null — ещё не спрашивали. */
    val backendStatus: MobileBackend.Status? = null,

    /** Запрос к бэкенду в процессе: на главном экране крутится ожидание. */
    val backendChecking: Boolean = false,

    /** На бэкенде настроена группа поддержки — показываем чат в настройках.
     *  Начальное значение — из прошлого ответа бэкенда (см. MainViewModel). */
    val supportEnabled: Boolean = false,

    /** На бэкенде настроено продление — показываем раздел «Подписка». */
    val renewEnabled: Boolean = false,

    /** Сколько дней осталось, когда срок на исходе: показываем окно с
     *  предложением продлить. null — напоминать не о чем либо человек
     *  уже ответил в этот запуск. */
    val renewReminder: Int? = null,

    /** Подписки нет — открыть вход по QR с телефона. Триала на
     *  телевизоре нет: он входит, как настольная версия. */
    val needLogin: Boolean = false,

    /** Предложение триала: показываем окно и ждём согласия.
     *  null — предлагать нечего либо человек уже ответил. */
    val trialOffer: TrialOffer? = null,

    /** По чему выстроен список серверов. */
    val sortBy: ServerSort = ServerSort.Ping,

    /** Тумблер «российские сервисы напрямую». По умолчанию включён. */
    val ruDirect: Boolean = true,

    /** Есть сборка новее установленной — в настройках кнопка «Обновить». */
    val update: AppUpdater.Update? = null,

    /** Обновление скачивается: проценты. null — не качаем. */
    val updateProgress: Int? = null,

    /** Сколько серверов в каждой группе. Пустые на экране не показываем:
     *  раздел вроде «Local (0)» занимал место, а нажать в нём было не на что. */
    val groupCounts: Map<String, Int> = emptyMap(),
)

/**
 * All possible user interaction intents
 */
sealed interface MainAction {
    data object Initialize : MainAction
    data object RefreshGroups : MainAction
    data object ToggleService : MainAction
    data object TestCurrentServer : MainAction
    data object TestAllServers : MainAction
    data object TestRealAllServers : MainAction
    data object CancelTesting : MainAction
    data object RemoveAllServers : MainAction
    data object RemoveDuplicateServers : MainAction
    data object RemoveInvalidServers : MainAction
    data object SortByTestResults : MainAction
    data object UpdateSubscriptions : MainAction
    data object ExportAll : MainAction

    data object ImportQRcode : MainAction
    data object ImportClipboard : MainAction
    data object ImportConfigLocal : MainAction
    data class ImportManually(val type: Int) : MainAction
    data object RestartService : MainAction
    data object LocateSelectedServer : MainAction

    data class SelectGroup(val groupId: String) : MainAction
    data class SelectServer(val guid: String) : MainAction
    data class RemoveServer(val guid: String) : MainAction
    data class EditServer(val guid: String, val profile: com.v2ray.ang.dto.entities.ProfileItem) : MainAction
    data class Search(val query: String) : MainAction
    data class ShareQRCode(val guid: String) : MainAction
    data class ShareClipboard(val guid: String) : MainAction
    data class ShareFullContent(val guid: String) : MainAction
    data object DismissQRCodeDialog : MainAction

    data class ImportBatchConfig(val configText: String) : MainAction

    data object LocateHandled : MainAction
}
