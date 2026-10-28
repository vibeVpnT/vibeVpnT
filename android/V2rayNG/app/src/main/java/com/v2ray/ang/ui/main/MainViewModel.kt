package com.v2ray.ang.ui.main

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.AppUpdater
import com.v2ray.ang.handler.MobileBackend
import com.v2ray.ang.handler.PushManager
import com.v2ray.ang.handler.isProfileInUse
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.LocateTarget
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.extension.delay
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.matchesPattern
import com.v2ray.ang.extension.moveItem
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.PatternSyntaxException

private fun applyTestDelayResults(
    servers: List<ServersCache>,
    updates: Map<String, Long>,
): List<ServersCache> = servers.map { server ->
    val delayMillis = updates[server.guid]
    if (delayMillis == null || delayMillis == server.testDelayMillis) {
        server
    } else {
        server.copy(testDelayMillis = delayMillis)
    }
}

private fun applyTestDelayResultsToRows(
    rows: List<ServerRowUiModel>,
    updates: Map<String, Long>,
): List<ServerRowUiModel> = rows.map { row ->
    val delayMillis = updates[row.guid]
    if (delayMillis == null || delayMillis == row.testDelayMillis) {
        row
    } else {
        row.copy(testDelayMillis = delayMillis)
    }
}

class MainViewModel(
    application: Application,
    private val dataSource: MainDataSource
) : BaseViewModel(application) {

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
    private val preloadDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    // ---------- UI state ----------
    private val _uiState = MutableStateFlow(
        MainUiState(
            selectedGroupId = dataSource.getSelectedSubscriptionId(),
            selectedGuid = dataSource.getSelectServer(),
            selectedRemarks = dataSource.getSelectServer()
                ?.let { dataSource.decodeServerConfig(it)?.remarks }.orEmpty(),
            confirmRemove = dataSource.getConfirmRemove(),
            doubleColumnDisplay = dataSource.getDoubleColumnDisplay(),
            ruDirect = MobileBackend.ruDirectEnabled,
            // Что было в прошлый раз — сразу, не дожидаясь бэкенда: иначе
            // до его ответа «Поддержка» вела в Telegram, а после — в чат.
            supportEnabled = MobileBackend.supportEnabledCached,
            renewEnabled = MobileBackend.renewEnabledCached,
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // ---------- Keyword filtering ----------
    @Volatile
    private var keywordFilter: String = ""
    private var filterJob: Job? = null

    // Группы, для которых уже пробовали подтянуть серверы автоматически.
    private val autoUpdatedGroups = mutableSetOf<String>()

    // ---------- Groups & cache ----------
    private val cacheMutex = Mutex()
    private val groupDataCache = mutableMapOf<String, List<ServersCache>>()
    private val groupUiFlows = ConcurrentHashMap<String, MutableStateFlow<ServerGroupUiState>>()
    private val groupServerFlows = ConcurrentHashMap<String, StateFlow<List<ServersCache>>>()
    private val groupLoadMutexes = ConcurrentHashMap<String, Mutex>()
    private val serverOrderPersistenceJobs = mutableMapOf<String, Job>()

    private var setupGroupJob: Job? = null
    private var preloadJob: Job? = null
    private var selectedGroupLoadJob: Job? = null
    private var reloadJob: Job? = null
    private var testResultFlushJob: Job? = null
    private val pendingTestResults = linkedMapOf<String, Long>()

    private val testRequests = MainTestRequests()
    private var bulkTestJob: Job? = null

    private val initialPageReady = CompletableDeferred<Unit>()

    // ---------- Service events ----------
    init {
        collectServiceEvents()
        setupGroupTab()
    }

    private fun collectServiceEvents() {
        viewModelScope.launch {
            dataSource.mainServiceEvent.collect { event ->
                handleServiceEvent(event)
            }
        }
    }

    private fun handleServiceEvent(event: MainServiceEvent) {
        when (event) {
            MainServiceEvent.StateRunning -> updateRunningState(true, clearTestingText = false)
            MainServiceEvent.StateNotRunning -> updateRunningState(false, clearTestingText = false)
            MainServiceEvent.StateStartSuccess -> {
                toastSuccess(R.string.toast_services_success)
                updateRunningState(true)
            }

            is MainServiceEvent.StateStartFailure -> {
                if (!event.message.isNullOrBlank()) {
                    toastError(event.message)
                } else {
                    toastError(R.string.toast_services_failure)
                }
                updateRunningState(false)
            }

            MainServiceEvent.StateStopSuccess -> updateRunningState(false)
            is MainServiceEvent.MeasureDelayResult -> {
                if (!uiState.value.isRunning || !testRequests.completeCurrent(event.requestId)) return
                _uiState.update { it.copy(isTesting = testRequests.isTesting, status = MainStatus.ConnectionTest(event.result)) }
            }

            is MainServiceEvent.MeasureConfigSuccess -> {
                val request = testRequests.bulk?.takeIf { it.id == event.requestId } ?: return
                queueTestResult(event.result, request)
            }

            is MainServiceEvent.MeasureConfigNotify -> {
                if (event.requestId == testRequests.bulk?.id) {
                    _uiState.update { it.copy(status = MainStatus.TestProgress(event.progress)) }
                }
            }

            is MainServiceEvent.MeasureConfigFinish -> {
                val request = testRequests.bulk?.takeIf { it.id == event.requestId } ?: return
                val scheduledFlush = testResultFlushJob
                testResultFlushJob = viewModelScope.launch {
                    scheduledFlush?.cancelAndJoin()
                    if (testRequests.bulk?.id != request.id) return@launch
                    flushPendingTestResults(request)
                    onTestsFinished(request.id)
                }
            }

            is MainServiceEvent.MeasureDelayCancelled -> {
                if (testRequests.completeCurrent(event.requestId)) resetTestStatus()
            }

            is MainServiceEvent.MeasureConfigCancelled -> {
                if (testRequests.completeBulk(event.requestId) != null) {
                    cancelPendingTestResults()
                    resetTestStatus()
                }
            }
        }
    }

    private fun queueTestResult(result: RealPingResult, request: MainTestRequests.Bulk) {
        pendingTestResults[result.guid] = result.delayMillis
        if (testResultFlushJob?.isActive == true) return

        testResultFlushJob = viewModelScope.launch {
            while (pendingTestResults.isNotEmpty()) {
                delay(TEST_RESULT_FLUSH_INTERVAL_MS)
                flushPendingTestResults(request)
            }
        }
    }

    private suspend fun flushPendingTestResults(request: MainTestRequests.Bulk) {
        if (pendingTestResults.isEmpty()) return
        if (testRequests.bulk?.id != request.id) return

        val updates = cacheMutex.withLock {
            val drained = pendingTestResults.toMap()
            pendingTestResults.clear()
            // Мерим все группы разом, поэтому и результаты раскладываем
            // по всем: ключ — guid, а он уникален.
            groupDataCache.keys.toList().forEach { id ->
                groupDataCache[id] = applyTestDelayResults(groupDataCache[id].orEmpty(), drained)
            }
            drained
        }
        if (updates.isEmpty()) return
        if (testRequests.bulk?.id != request.id) return
        uiState.value.groups.forEach { group ->
            mutableServerGroupState(group.id).update { current ->
                current.copy(
                    servers = applyTestDelayResults(current.servers, updates),
                    rows = sortRows(applyTestDelayResultsToRows(current.rows, updates)),
                )
            }
        }
    }

    internal fun formatStatus(status: MainStatus): String = when (status) {
        MainStatus.Disconnected -> dataSource.getString(R.string.connection_not_connected)
        MainStatus.Connected -> dataSource.getString(R.string.connection_connected)
        MainStatus.Testing -> dataSource.getString(R.string.connection_test_testing)
        is MainStatus.TestProgress -> dataSource.getString(
            R.string.connection_running_task_left,
            status.progress
        )

        is MainStatus.ConnectionTest -> formatConnectionTestResult(status.result)
    }

    private fun formatConnectionTestResult(result: ConnectionTestResult): String {
        val status = if (result.delayMillis >= 0) {
            val delay = dataSource.getString(R.string.server_test_delay_value, result.delayMillis)
            dataSource.getString(R.string.connection_test_available, delay)
        } else {
            val detail = result.errorMessage.ifBlank {
                dataSource.getString(R.string.connection_test_empty_message)
            }
            dataSource.getString(R.string.connection_test_error, detail)
        }

        if (result.delayMillis < 0 || (result.country == null && result.ipAddress == null)) {
            return status
        }

        val unknown = dataSource.getString(R.string.value_unknown)
        return "$status\n(${result.country ?: unknown}) ${result.ipAddress ?: unknown}"
    }

    // ---------- Public state accessors ----------
    fun serversForGroup(groupId: String): StateFlow<List<ServersCache>> =
        groupServerFlows.computeIfAbsent(groupId) {
            val groupState = mutableServerGroupState(groupId)
            groupState
                .map { it.servers }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                    initialValue = groupState.value.servers,
                )
        }

    internal fun serverGroupState(groupId: String): StateFlow<ServerGroupUiState> =
        mutableServerGroupState(groupId).asStateFlow()

    private fun mutableServerGroupState(groupId: String): MutableStateFlow<ServerGroupUiState> =
        groupUiFlows.computeIfAbsent(groupId) { MutableStateFlow(ServerGroupUiState()) }

    private fun currentServers(): List<ServersCache> =
        mutableServerGroupState(uiState.value.selectedGroupId).value.servers

    // ---------- Action handler ----------
    fun onAction(action: MainAction) {
        when (action) {
            MainAction.Initialize -> initialize()
            MainAction.RefreshGroups -> setupGroupTab(forceRefresh = true)
            MainAction.TestAllServers -> testAllRealPing(true)
            MainAction.TestRealAllServers -> testAllRealPing()
            MainAction.CancelTesting -> cancelAllPing()
            MainAction.RemoveAllServers -> removeAllServerAsync()
            MainAction.RemoveDuplicateServers -> removeDuplicateServerAsync()
            MainAction.RemoveInvalidServers -> removeInvalidServerAsync()
            MainAction.SortByTestResults -> sortByTestResultsAsync()
            MainAction.UpdateSubscriptions -> {
                importConfigViaSub()
                // Заодно перекачиваем правила: кнопка «обновить подписку»
                // для человека означает «подтяни всё свежее».
                refreshRouting(force = true)
            }
            MainAction.ExportAll -> exportAllAsync()
            is MainAction.SelectGroup -> subscriptionIdChanged(action.groupId)
            is MainAction.SelectServer -> updateSelectedGuid(action.guid)
            is MainAction.RemoveServer -> removeServerAndRefresh(action.guid)
            is MainAction.Search -> filterConfig(action.query)
            is MainAction.ImportBatchConfig -> importBatchConfig(action.configText)
            MainAction.LocateHandled -> consumeLocateTarget()
            is MainAction.ShareQRCode -> {
                val bitmap = dataSource.share2QRCode(action.guid)
                _uiState.update { it.copy(shareQRCodeBitmap = bitmap) }
            }

            MainAction.DismissQRCodeDialog -> {
                _uiState.update { it.copy(shareQRCodeBitmap = null) }
            }

            MainAction.ToggleService,
            MainAction.TestCurrentServer,
            MainAction.ImportQRcode,
            MainAction.ImportClipboard,
            MainAction.ImportConfigLocal,
            is MainAction.ImportManually,
            MainAction.RestartService,
            MainAction.LocateSelectedServer,
            is MainAction.EditServer,
            is MainAction.ShareClipboard,
            is MainAction.ShareFullContent -> {
                // Handled by Activity via its onAction lambda
            }
        }
    }

    // ---------- Initialization ----------
    fun initialize() {
        viewModelScope.launch(preloadDispatcher) {
            try {
                initialPageReady.await()
                delay(32)
                dataSource.initAssets()
                dataSource.syncSubscriptions()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Main background initialization failed", error)
            }
        }
        provisionSubscription()
        checkUpdate()
    }

    // ---------- Обновление приложения ----------

    /** Есть ли сборка новее — спрашиваем бэкенд при каждом запуске. */
    fun checkUpdate() {
        if (!MobileBackend.isConfigured) return
        viewModelScope.launch(ioDispatcher) {
            val update = try {
                AppUpdater.check(getApplication())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Не узнали, есть ли обновление", error)
                null
            }
            _uiState.update { it.copy(update = update) }
        }
    }

    /** Кнопка «Обновить»: скачать apk и отдать системе на установку. */
    fun startUpdate() {
        val update = _uiState.value.update ?: return
        if (_uiState.value.updateProgress != null) return
        _uiState.update { it.copy(updateProgress = 0) }

        viewModelScope.launch(ioDispatcher) {
            try {
                val file = AppUpdater.download(getApplication(), update) { percent ->
                    _uiState.update { it.copy(updateProgress = percent) }
                }
                withContext(Dispatchers.Main) { AppUpdater.install(getApplication(), file) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Обновление не скачалось", error)
                withContext(Dispatchers.Main) {
                    toastError(getString(R.string.update_download_failed, error.message.orEmpty()))
                }
            } finally {
                _uiState.update { it.copy(updateProgress = null) }
            }
        }
    }

    /**
     * Спрашивает подписку у нашего бэкенда.
     *
     * Делается при каждом запуске, а не только при первом: тем же ответом
     * приходит, не истёк ли срок, и подтягиваются серверы, которые мы
     * добавили на своей стороне.
     */
    private fun provisionSubscription() {
        if (!MobileBackend.isConfigured) return
        askBackend(claim = false)
    }

    /** Человек согласился на триал — только теперь просим выдать ключ.
     *  Аккаунта нет — он заводится тем же запросом. */
    fun claimTrial() {
        _uiState.update { it.copy(trialOffer = null, accountWelcome = false) }
        askBackend(claim = true)
    }

    fun dismissAccountWelcome() {
        _uiState.update { it.copy(accountWelcome = false) }
    }

    /** «Я сохранил номер». Не дошло до сервера — спросим в следующий раз. */
    fun confirmAccountNumber() {
        _uiState.update { it.copy(numberToSave = null) }
        viewModelScope.launch(ioDispatcher) {
            if (!MobileBackend.confirmNumber()) {
                LogUtil.w(AppConfig.TAG, "Подтверждение номера аккаунта не дошло")
            }
        }
    }

    fun dismissAccountNumber() {
        _uiState.update { it.copy(numberToSave = null) }
    }

    /**
     * Вернулись с другого экрана. Если там вошли в аккаунт или вышли из
     * него, подписка и серверы теперь другие — спрашиваем бэкенд заново.
     */
    fun refreshAccountIfChanged() {
        if (!MobileBackend.consumeAccountChanged()) return
        viewModelScope.launch {
            cacheMutex.withLock { groupDataCache.clear() }
            setupGroupTab(forceRefresh = true).join()
        }
        askBackend(claim = false)
    }

    /**
     * Перекачивает и применяет правила маршрутизации.
     *
     * force — применить даже при той же версии: так делаем по кнопке
     * «обновить» и после переключения тумблера.
     */
    fun refreshRouting(force: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            try {
                MobileBackend.syncRouting(force)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Правила — дело десятое: без них приложение работает,
                // просто всё пойдёт через сервер.
                LogUtil.e(AppConfig.TAG, "Правила маршрутизации не обновились", error)
            }
        }
    }

    /** Тумблер «российские сервисы напрямую». */
    fun setRuDirect(enabled: Boolean) {
        MobileBackend.ruDirectEnabled = enabled
        _uiState.update { it.copy(ruDirect = enabled) }
        refreshRouting(force = true)
    }

    /**
     * Нужно ли напомнить, что срок кончается.
     *
     * Напоминаем за три дня и при каждом запуске: приложение открывают
     * не каждый день, и одного уведомления человек не заметит. Срок,
     * который ещё не пошёл, и бессрочный ключ не трогаем — напоминать
     * там не о чем.
     */
    private fun renewReminder(result: MobileBackend.Result): Int? {
        if (!result.renewEnabled || result.startsOnFirstUse) return null

        val daysLeft = result.daysLeft ?: return null
        return daysLeft.takeIf { it <= RENEW_REMINDER_DAYS }
    }

    /** Человек ответил на напоминание — в этот запуск больше не трогаем. */
    fun dismissRenewReminder() {
        _uiState.update { it.copy(renewReminder = null) }
    }

    /** Отказался. Предложим снова при следующем запуске: без подписки
     *  приложению всё равно нечего делать. */
    fun dismissTrialOffer() {
        _uiState.update { it.copy(trialOffer = null) }
    }

    private fun askBackend(claim: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(backendChecking = true) }

            val result = try {
                MobileBackend.provision(getApplication(), claim = claim)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Не удалось получить подписку с бэкенда", error)
                MobileBackend.Result(MobileBackend.Status.NETWORK_ERROR)
            }

            // Не достучались — оставляем то, что знали раньше. Иначе
            // одна неудачная попытка на запуске (нет сети, бэкенд
            // перезапускается) убирала бы «Подписку» и «Поддержку» из
            // меню до следующего запуска: человек платил и обнаруживал,
            // что вкладки нет.
            val reachable = result.status != MobileBackend.Status.NETWORK_ERROR

            _uiState.update {
                it.copy(
                    backendChecking = false,
                    backendStatus = result.status,
                    supportEnabled = if (reachable) result.supportEnabled else it.supportEnabled,
                    renewEnabled = if (reachable) result.renewEnabled else it.renewEnabled,
                    renewReminder = if (reachable) renewReminder(result) else it.renewReminder,
                    trialOffer = if (result.status == MobileBackend.Status.OFFER) {
                        TrialOffer(days = result.trialDays, gb = result.trialGb)
                    } else {
                        null
                    },
                    accountWelcome = result.status == MobileBackend.Status.NEEDS_ACCOUNT,
                    // Номер навязываем, пока его не сохранили: без него
                    // не войти ни на другом устройстве, ни после сброса.
                    numberToSave = if (reachable && !result.accountConfirmed) {
                        result.accountNumber.ifBlank { null }
                    } else {
                        null
                    },
                )
            }

            // Адрес для push: сообщаем его при каждом запуске. Firebase
            // меняет его по своим причинам, а бэкенд должен знать
            // нынешний — иначе уведомление уедет в никуда.
            if (reachable) PushManager.register(getApplication())

            // Правила тянем молча: версия сверяется внутри, лишний раз
            // ничего не перекачивается.
            refreshRouting(force = false)

            if (result.serversImported > 0) {
                cacheMutex.withLock { groupDataCache.clear() }
                setupGroupTab(forceRefresh = true).join()
                selectFirstServerIfNone()
            }
        }
    }

    /**
     * Выбирает первый сервер, если человек ещё ничего не выбирал.
     *
     * Без этого после первой настройки кнопка подключения ничего бы не
     * сделала: серверы есть, а выбранного нет.
     */
    private fun selectFirstServerIfNone() {
        if (!uiState.value.selectedGuid.isNullOrEmpty()) return

        val first = currentServers().firstOrNull() ?: return
        updateSelectedGuid(first.guid)
        LogUtil.i(AppConfig.TAG, "Выбран сервер по умолчанию: ${first.guid}")
    }

    fun refreshUiSettings() {
        _uiState.update {
            it.copy(
                confirmRemove = dataSource.getConfirmRemove(),
                doubleColumnDisplay = dataSource.getDoubleColumnDisplay()
            )
        }
    }

    // ---------- Group & server loading ----------
    private suspend fun buildServersCache(guids: List<String>): List<ServersCache> =
        guids.mapNotNull { guid ->
            currentCoroutineContext().ensureActive()
            val profile = dataSource.decodeServerConfig(guid) ?: return@mapNotNull null
            val affiliation = dataSource.decodeAffiliationInfo(guid)
            ServersCache(
                guid = guid,
                profile = profile.copy(),
                testDelayMillis = affiliation?.testDelayMillis ?: 0L
            )
        }

    private suspend fun loadGroup(
        groupId: String,
        forceRefresh: Boolean = false
    ): List<ServersCache> {
        val loadMutex = groupLoadMutexes.computeIfAbsent(groupId) { Mutex() }
        return loadMutex.withLock {
            if (!forceRefresh) {
                cacheMutex.withLock { groupDataCache[groupId]?.let { return@withLock it } }
            }
            val servers = buildServersCache(dataSource.getServerGuidList(groupId))
            currentCoroutineContext().ensureActive()
            cacheMutex.withLock { groupDataCache[groupId] = servers }
            servers
        }
    }

    private fun applyKeywordFilter(servers: List<ServersCache>): List<ServersCache> {
        val keyword = keywordFilter.trim()
        if (keyword.isEmpty()) return servers
        val regex = try {
            Regex(keyword, RegexOption.IGNORE_CASE)
        } catch (_: PatternSyntaxException) {
            return servers
        }
        return servers.filter { cache ->
            val profile = cache.profile
            profile.remarks.matchesPattern(regex, keyword) ||
                    profile.description.orEmpty().matchesPattern(regex, keyword) ||
                    profile.server.orEmpty().matchesPattern(regex, keyword) ||
                    profile.configType.name.matchesPattern(regex, keyword)
        }
    }

    private fun updateGroupUi(groupId: String, servers: List<ServersCache>) {
        val filteredServers = applyKeywordFilter(servers)
        val rows = buildServerRows(groupId, filteredServers)
        mutableServerGroupState(groupId).value = ServerGroupUiState(
            servers = filteredServers,
            rows = rows
        )
        // Счётчик нужен экрану, чтобы не рисовать пустые группы.
        _uiState.update { it.copy(groupCounts = it.groupCounts + (groupId to rows.size)) }
    }

    /**
     * Порядок строк на экране.
     *
     * Сортируем только отображение, а не сохранённый список: человек
     * меняет вид туда-обратно, и переписывать из-за этого хранилище
     * незачем. «Как добавлены» — исходный порядок, он и лежит в базе.
     */
    private fun sortRows(rows: List<ServerRowUiModel>): List<ServerRowUiModel> =
        when (uiState.value.sortBy) {
            ServerSort.Manual -> rows
            ServerSort.Name -> rows.sortedBy { it.remarks.lowercase() }
            // Непромеренные и недоступные — в конец: иначе нули оказались
            // бы впереди самых быстрых.
            ServerSort.Ping -> rows.sortedBy {
                if (it.testDelayMillis <= 0L) Long.MAX_VALUE else it.testDelayMillis
            }
        }

    fun setSort(sort: ServerSort) {
        if (uiState.value.sortBy == sort) return
        _uiState.update { it.copy(sortBy = sort) }
        // Перестраиваем уже загруженные группы, не трогая хранилище.
        uiState.value.groups.forEach { group ->
            mutableServerGroupState(group.id).update { it.copy(rows = sortRows(it.rows)) }
        }
    }

    private fun buildServerRows(groupId: String, servers: List<ServersCache>): List<ServerRowUiModel> {
        val subscriptionRemarks = if (groupId.isEmpty()) {
            servers.asSequence()
                .map { it.profile.subscriptionId }
                .filter { it.isNotEmpty() }
                .distinct()
                .associateWith { subscriptionId ->
                    dataSource.getSubscriptionItem(subscriptionId)?.remarks.orEmpty()
                }
        } else {
            emptyMap()
        }
        return sortRows(
            servers.map { server ->
                buildServerRowUiModel(
                    server = server,
                    subscriptionRemarks = subscriptionRemarks[server.profile.subscriptionId].orEmpty()
                )
            }
        )
    }

    fun getSubscriptions(): List<SubscriptionCache> = dataSource.getSubscriptions()

    /** Подписка группы — для шапки: остаток трафика, срок, правка. */
    fun subscriptionOf(groupId: String): SubscriptionItem? =
        if (groupId.isEmpty()) null else dataSource.getSubscriptionItem(groupId)

    /** Выгружает ссылки серверов группы в буфер обмена. */
    fun exportGroup(groupId: String) {
        viewModelScope.launch(ioDispatcher) {
            val guids = serverGroupState(groupId).value.servers.map { it.guid }
            if (guids.isEmpty()) return@launch

            val count = dataSource.shareNonCustomConfigsToClipboard(guids)
            if (count > 0) toastSuccess(R.string.toast_success) else toastError(R.string.toast_failure)
        }
    }

    /** Сохраняет новое название и адрес подписки. */
    /**
     * Сохраняет подписку: с пустым groupId заводит новую.
     *
     * Сразу после добавления подписку обновляем: иначе группа появится
     * пустой, и человеку пришлось бы догадываться нажать «обновить».
     */
    fun saveSubscription(groupId: String, item: SubscriptionItem) {
        viewModelScope.launch(ioDispatcher) {
            dataSource.updateSubscriptionItem(groupId, item)
            // Название видно в шапке группы — перечитываем список.
            setupGroupTab(forceRefresh = true)
            if (groupId.isEmpty()) {
                importConfigViaSub()
            }
        }
    }


    private fun resolveSelectedGroup(groups: List<GroupMapItem>): String {
        val current = uiState.value.selectedGroupId
        val resolved = when {
            groups.isEmpty() -> ""
            groups.any { it.id == current } -> current
            else -> groups.first().id
        }
        if (resolved != current) {
            dataSource.setSelectedSubscriptionId(resolved)
        }
        return resolved
    }

    private fun radialPreloadOrder(groups: List<GroupMapItem>, selectedIndex: Int): List<String> {
        if (groups.isEmpty()) return emptyList()
        val result = ArrayList<String>((groups.size - 1).coerceAtLeast(0))
        for (distance in 1 until groups.size) {
            val right = selectedIndex + distance
            val left = selectedIndex - distance
            if (right in groups.indices) result += groups[right].id
            if (left in groups.indices) result += groups[left].id
        }
        return result
    }

    fun setupGroupTab(forceRefresh: Boolean = false): Job {
        setupGroupJob?.cancel()
        preloadJob?.cancel()
        selectedGroupLoadJob?.cancel()

        return viewModelScope.launch(ioDispatcher) {
            try {
                if (forceRefresh) {
                    cacheMutex.withLock { groupDataCache.clear() }
                }
                val groups = dataSource.getSubscriptions().map {
                    GroupMapItem(id = it.guid, remarks = it.subscription.remarks)
                }
                val selectedGroup = resolveSelectedGroup(groups)
                val validIds = groups.mapTo(HashSet()) { it.id }
                groupUiFlows.keys.removeAll { it !in validIds }
                groupServerFlows.keys.removeAll { it !in validIds }
                groupLoadMutexes.keys.removeAll { it !in validIds }

                _uiState.update {
                    it.copy(
                        groups = groups,
                        selectedGroupId = selectedGroup,
                        selectedGuid = dataSource.getSelectServer(),
                        selectedRemarks = remarksOf(dataSource.getSelectServer()),
                    )
                }
                groups.forEach { mutableServerGroupState(it.id) }

                if (groups.isEmpty()) {
                    cacheMutex.withLock { groupDataCache.clear() }
                    return@launch
                }

                val selectedServers = loadGroup(selectedGroup, forceRefresh)
                updateGroupUi(selectedGroup, selectedServers)

                if (!initialPageReady.isCompleted) {
                    initialPageReady.complete(Unit)
                }

                val selectedIndex =
                    groups.indexOfFirst { it.id == selectedGroup }.coerceAtLeast(0)
                val preloadOrder = radialPreloadOrder(groups, selectedIndex)
                preloadJob = viewModelScope.launch(preloadDispatcher) {
                    preloadOrder.forEach { groupId ->
                        ensureActive()
                        delay(32)
                        val servers = loadGroup(groupId, forceRefresh)
                        updateGroupUi(groupId, servers)
                    }
                }

                autoUpdateNewSubscriptions(groups)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to set up group tabs", error)
            } finally {
                if (!initialPageReady.isCompleted) {
                    initialPageReady.complete(Unit)
                }
            }
        }.also { setupGroupJob = it }
    }

    /**
     * Сама подтягивает серверы для только что добавленной подписки.
     *
     * Без этого только что заведённая подписка выглядит сломанной: группа
     * есть, серверов нет, а обновить её нужно догадаться вручную.
     *
     * Каждую группу пробуем один раз за запуск: если подписка пустая
     * из-за нерабочего адреса, бесконечно дёргать её незачем.
     */
    private fun autoUpdateNewSubscriptions(groups: List<GroupMapItem>) {
        val fresh = groups.filter { group ->
            group.id.isNotEmpty() &&
                group.id !in autoUpdatedGroups &&
                dataSource.getServerGuidList(group.id).isEmpty()
        }
        if (fresh.isEmpty()) return

        autoUpdatedGroups += fresh.map { it.id }
        viewModelScope.launch {
            // Небольшая пауза: человек успевает увидеть, что группа
            // появилась, и обновление не выглядит мельканием.
            delay(NEW_SUBSCRIPTION_UPDATE_DELAY_MS)
            LogUtil.i(
                AppConfig.TAG,
                "Новые подписки без серверов: ${fresh.joinToString { it.remarks }} — обновляю"
            )
            importConfigViaSub()
        }
    }

    // ---------- Business actions (coroutine-based) ----------
    private fun importBatchConfig(configText: String) {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val (count, countSub) = dataSource.importBatchConfig(
                        configText, uiState.value.selectedGroupId, true
                    )
                    when {
                        count > 0 -> {
                            toast(dataSource.getString(R.string.title_import_config_count, count))
                            setupGroupTab(forceRefresh = true)
                        }

                        countSub > 0 -> setupGroupTab(forceRefresh = true)
                        else -> toastError(R.string.toast_failure)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Failed to import batch config", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun importConfigViaSub() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    // Обновляем все подписки, а не «выбранную». Выбранной
                    // человек больше не управляет — вкладок групп нет, — и
                    // ею могла остаться пустая запись: тогда обновление
                    // молча пропускалось, а настоящая подписка так и
                    // оставалась старой.
                    val result = dataSource.updateConfigViaSubAll()
                    when {
                        result.successCount + result.failureCount + result.skipCount == 0 ->
                            toast(R.string.title_update_subscription_no_subscription)

                        result.successCount > 0 && result.failureCount + result.skipCount == 0 ->
                            toast(
                                getQuantityString(
                                    R.plurals.title_update_config_count,
                                    result.configCount,
                                    result.configCount,
                                )
                            )

                        else ->
                            toast(dataSource.getString(R.string.title_update_subscription_result, result.configCount, result.successCount, result.failureCount, result.skipCount))
                    }
                    if (result.configCount > 0) {
                        setupGroupTab(forceRefresh = true)
                        refreshSelectedGuid()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Subscription update failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun exportAllAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val groupId = uiState.value.selectedGroupId
                    val list = if (groupId.isEmpty() && keywordFilter.isEmpty()) {
                        dataSource.getServerGuidList("")
                    } else {
                        currentServers().map { it.guid }
                    }
                    val ret = dataSource.shareNonCustomConfigsToClipboard(list)
                    if (ret > 0) {
                        toast(dataSource.getString(R.string.title_export_config_count, ret))
                    } else {
                        toastError(R.string.toast_failure)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Export failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeAllServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val count =
                        if (uiState.value.selectedGroupId.isEmpty() && keywordFilter.isEmpty()) {
                            dataSource.removeAllServer()
                        } else {
                            val guids = currentServers().map { it.guid }
                            guids.forEach { dataSource.removeServer(it) }
                            guids.size
                        }
                    viewModelScope.launch(ioDispatcher) {
                        cacheMutex.withLock { groupDataCache.clear() }
                    }
                    setupGroupTab(forceRefresh = true)
                    toast(dataSource.getString(R.string.title_del_config_count, count))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete all failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeDuplicateServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val seen = HashSet<ProfileItem>()
                    val duplicates = ArrayList<String>()
                    currentServers().forEach { server ->
                        val profile = server.profile
                        if (!profile.configType.isComplexType()) {
                            val identity = profile.duplicateIdentity()
                            if (!seen.add(identity)) duplicates += server.guid
                        }
                    }
                    duplicates.forEach { dataSource.removeServer(it) }
                    setupGroupTab(forceRefresh = true)
                    toast(dataSource.getString(R.string.title_del_duplicate_config_count, duplicates.size))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete duplicate failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeInvalidServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val count = removeInvalidServerInternal()
                    viewModelScope.launch(ioDispatcher) {
                        cacheMutex.withLock { groupDataCache.clear() }
                        setupGroupTab(forceRefresh = true)
                    }
                    toast(dataSource.getString(R.string.title_del_config_count, count))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete invalid failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeInvalidServerInternal(): Int {
        val visibleServersOnly =
            uiState.value.selectedGroupId.isNotEmpty() || keywordFilter.isNotBlank()
        return if (visibleServersOnly) {
            currentServers().sumOf { server ->
                dataSource.removeInvalidServerByGuid(server.guid)
            }
        } else {
            dataSource.removeInvalidServersInGroup("")
        }
    }

    private fun sortByTestResultsAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    sortByTestResultsInternal()
                    cacheMutex.withLock { groupDataCache.clear() }
                    setupGroupTab(forceRefresh = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Sort by test results failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun sortByTestResultsInternal() {
        val subs = if (uiState.value.selectedGroupId.isEmpty()) {
            dataSource.getSubsList()
        } else {
            listOf(uiState.value.selectedGroupId)
        }
        subs.forEach { dataSource.sortByTestResultsForSub(it) }
    }

    fun subscriptionIdChanged(id: String) {
        if (_uiState.value.groups.none { it.id == id }) return
        mutableServerGroupState(id)
        if (uiState.value.selectedGroupId != id) {
            dataSource.setSelectedSubscriptionId(id)
            _uiState.update { it.copy(selectedGroupId = id) }
        }
        selectedGroupLoadJob?.cancel()
        selectedGroupLoadJob = viewModelScope.launch(ioDispatcher) {
            try {
                updateGroupUi(id, loadGroup(id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to load selected group: $id", error)
            }
        }
    }

    fun reloadServerList() {
        val groupId = uiState.value.selectedGroupId
        selectedGroupLoadJob?.cancel()
        selectedGroupLoadJob = viewModelScope.launch(ioDispatcher) {
            updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
        }
    }

    fun reloadAllGroups(groupIds: List<String>) {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch(preloadDispatcher) {
            val selected = uiState.value.selectedGroupId
            val order = buildList {
                if (selected in groupIds) add(selected)
                addAll(groupIds.filter { it != selected })
            }
            order.forEachIndexed { index, groupId ->
                ensureActive()
                if (index > 0) delay(32)
                updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
            }
        }
    }

    fun filterConfig(keyword: String) {
        if (keyword == keywordFilter) return
        keywordFilter = keyword
        filterJob?.cancel()
        filterJob = viewModelScope.launch(defaultDispatcher) {
            delay(300)
            val snapshot = cacheMutex.withLock { groupDataCache.toMap() }
            ensureActive()
            snapshot.forEach { (groupId, servers) ->
                ensureActive()
                updateGroupUi(groupId, servers)
            }
        }
    }

    fun updateSelectedGuid(guid: String) {
        dataSource.setSelectServer(guid)
        _uiState.update { it.copy(selectedGuid = guid, selectedRemarks = remarksOf(guid)) }
    }

    fun refreshSelectedGuid() {
        val guid = dataSource.getSelectServer()
        _uiState.update { it.copy(selectedGuid = guid, selectedRemarks = remarksOf(guid)) }
    }

    /** Название сервера для главного экрана; пусто, если сервер не выбран. */
    private fun remarksOf(guid: String?): String =
        guid?.let { dataSource.decodeServerConfig(it)?.remarks }.orEmpty()

    fun removeServerAndRefresh(guid: String) {
        if (isProfileInUse(guid)) {
            toast(R.string.toast_profile_in_use)
            return
        }
        viewModelScope.launch(ioDispatcher) {
            dataSource.removeServer(guid)
            cacheMutex.withLock { groupDataCache.clear() }
            setupGroupTab(forceRefresh = true).join()
        }
    }

    fun moveServer(groupId: String, fromPosition: Int, toPosition: Int) {
        val groupState = mutableServerGroupState(groupId).value
        val servers = groupState.servers.toMutableList()
        if (!servers.moveItem(fromPosition, toPosition)) return
        val rows = groupState.rows.toMutableList()
        rows.moveItem(fromPosition, toPosition)
        val guids = servers.map { it.guid }
        mutableServerGroupState(groupId).value = ServerGroupUiState(servers, rows)
        // A drag emits several moves; serialize writes so an older order cannot overwrite a newer one.
        val previousPersistenceJob = serverOrderPersistenceJobs[groupId]
        serverOrderPersistenceJobs[groupId] = viewModelScope.launch(ioDispatcher) {
            previousPersistenceJob?.join()
            dataSource.encodeServerList(guids, groupId)
            cacheMutex.withLock { groupDataCache[groupId] = servers }
        }
    }

    // ---------- Testing ----------
    fun cancelAllPing() {
        bulkTestJob?.cancel()
        bulkTestJob = null
        testRequests.cancelBulk()
        testRequests.invalidateCurrent()
        cancelPendingTestResults()
        resetTestStatus()
        dataSource.cancelAllPing()
    }

    private fun resetTestStatus() {
        _uiState.update {
            it.copy(
                isTesting = testRequests.isTesting,
                status = if (testRequests.isTesting) MainStatus.Testing
                else if (it.isRunning) MainStatus.Connected else MainStatus.Disconnected
            )
        }
    }

    /**
     * Замер задержки ВСЕХ серверов, во всех группах.
     *
     * Раньше мерилась только «выбранная» группа. С тех пор как вкладки
     * групп убраны, выбранной могла остаться пустая — и кнопка молчала,
     * потому что мерить было нечего.
     */
    fun testAllRealPing(onlyTcp: Boolean = false) {
        cancelAllPing()
        val groupIds = uiState.value.groups.map { it.id }
        val serverGuids = groupIds.flatMap { id ->
            serverGroupState(id).value.servers.map { it.guid }
        }
        if (serverGuids.isEmpty()) {
            return
        }

        groupIds.forEach { id ->
            mutableServerGroupState(id).update { current ->
                current.copy(
                    servers = current.servers.map { server ->
                        if (server.testDelayMillis == 0L) server
                        else server.copy(testDelayMillis = 0L)
                    },
                    rows = current.rows.map { row ->
                        if (row.testDelayMillis == 0L) row
                        else row.copy(testDelayMillis = 0L)
                    }
                )
            }
        }

        val request = testRequests.beginBulk(uiState.value.selectedGroupId)
        // Список передаём явно: по одному лишь идентификатору подписки
        // служба померила бы только её.
        val message = TestServiceMessage(
            key = AppConfig.MSG_MEASURE_CONFIG_START,
            subscriptionId = "",
            serverGuids = serverGuids,
            onlyTcp = onlyTcp
        )
        _uiState.update {
            it.copy(
                isTesting = true,
                status = MainStatus.Testing
            )
        }
        bulkTestJob = viewModelScope.launch {
            withContext(ioDispatcher) {
                dataSource.clearAllTestDelayResults(serverGuids)
                val resetGuids = serverGuids.toHashSet()
                cacheMutex.withLock {
                    groupDataCache.keys.toList().forEach { id ->
                        groupDataCache[id] = groupDataCache[id].orEmpty().map { server ->
                            if (server.guid !in resetGuids || server.testDelayMillis == 0L) server
                            else server.copy(testDelayMillis = 0L)
                        }
                    }
                }
            }
            dataSource.sendMsg2TestService(message, request.id)
        }
    }

    private fun cancelPendingTestResults() {
        testResultFlushJob?.cancel()
        testResultFlushJob = null
        pendingTestResults.clear()
    }

    fun testCurrentServerRealPing() {
        if (!uiState.value.isRunning) return
        val requestId = testRequests.beginCurrent()
        _uiState.update { it.copy(isTesting = true, status = MainStatus.Testing) }
        dataSource.testCurrentServerRealPing(requestId)
    }

    private fun onTestsFinished(requestId: String) {
        if (testRequests.completeBulk(requestId) == null) return
        resetTestStatus()
        viewModelScope.launch(ioDispatcher) {
            cacheMutex.withLock { groupDataCache.clear() }
            reloadAllGroups(_uiState.value.groups.map { it.id })
        }
    }

    fun triggerLocateSelectedServer() {
        val selected = dataSource.getSelectServer() ?: return
        val profile = dataSource.decodeServerConfig(selected) ?: return
        val groupId = profile.subscriptionId
        if (_uiState.value.groups.none { it.id == groupId }) return
        viewModelScope.launch(ioDispatcher) {
            updateGroupUi(groupId, loadGroup(groupId))
            if (_uiState.value.selectedGroupId != groupId) {
                dataSource.setSelectedSubscriptionId(groupId)
            }
            val target = LocateTarget(groupId, selected)
            _uiState.update {
                it.copy(selectedGroupId = groupId, locateTarget = target)
            }
        }
    }

    private fun consumeLocateTarget() {
        _uiState.update { it.copy(locateTarget = null) }
    }

    // ---------- Running state ----------
    private fun updateRunningState(running: Boolean, clearTestingText: Boolean = true) {
        if (!running || clearTestingText) testRequests.invalidateCurrent()
        val wasRunning = uiState.value.isRunning
        _uiState.update { state ->
            state.copy(
                isRunning = running,
                isTesting = testRequests.isTesting,
                status = if (!clearTestingText && state.isRunning == running) state.status
                else if (running) MainStatus.Connected else MainStatus.Disconnected
            )
        }

        // Только что подключились — сразу проверяем соединение, чтобы на
        // экране была не просьба нажать, а готовый результат: задержка,
        // страна и адрес, с которым человека видят снаружи.
        if (running && !wasRunning) {
            viewModelScope.launch {
                delay(CONNECTION_TEST_DELAY_MS)
                if (uiState.value.isRunning) testCurrentServerRealPing()
            }
        }
    }

    override fun onCleared() {
        setupGroupJob?.cancel()
        preloadJob?.cancel()
        selectedGroupLoadJob?.cancel()
        reloadJob?.cancel()
        filterJob?.cancel()
        cancelAllPing()
        dataSource.close()
        super.onCleared()
    }

    // ---------- Factory ----------
    class Factory(private val application: Application, private val dataSource: MainDataSource) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                return MainViewModel(application, dataSource) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    private companion object {
        const val TEST_RESULT_FLUSH_INTERVAL_MS = 500L

        // За сколько дней до конца срока предлагаем продлить.
        const val RENEW_REMINDER_DAYS = 3

        // Пауза перед автоматическим обновлением только что добавленной
        // подписки.
        const val NEW_SUBSCRIPTION_UPDATE_DELAY_MS = 5_000L

        // Пауза перед проверкой соединения после подключения: ядру нужно
        // мгновение, чтобы поднять туннель.
        const val CONNECTION_TEST_DELAY_MS = 1_200L
    }
}
