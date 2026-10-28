package com.v2ray.ang.handler

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Base64
import android.provider.Settings
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.ui.compose.Brand
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Разговор приложения с нашим бэкендом.
 *
 * Устройство входит в аккаунт — номер из 16 цифр, один на все платформы
 * — и дальше шлёт только токен сессии. При запуске приложение по токену
 * получает готовый адрес подписки: человеку ничего не нужно вводить
 * руками. Тот же ответ говорит, жива подписка или срок вышел.
 *
 * Прежние версии называли себя Android ID. Обновившееся приложение один
 * раз меняет его на сессию (session/migrate) и больше его не шлёт.
 */
object MobileBackend {

    /** Состояния, которые присылает бэкенд. Любое другое считаем ошибкой. */
    enum class Status {
        ACTIVE,          // подписка работает
        EXPIRED,         // срок вышел
        LIMITED,         // трафик закончился
        DISABLED,        // ключ выключен вручную
        OFFER,           // ключа нет, но триал положен — надо спросить человека
        UNAVAILABLE,     // ключа нет и выдавать нечего
        RATE_LIMITED,    // с этого адреса уже набрали ключей
        BAD_CODE,        // код входа не подошёл
        PENDING,         // код показан, но его ещё никто не отсканировал
        NEEDS_ACCOUNT,   // устройство не вошло в аккаунт: создать или войти
        NETWORK_ERROR;   // до бэкенда не достучались

        /** Подписка пригодна к работе. */
        val isUsable: Boolean get() = this == ACTIVE
    }

    data class Result(
        val status: Status,
        val subscriptionUrl: String = "",
        val daysLeft: Int? = null,
        /** Срок ещё не пошёл: начнётся с первого подключения. */
        val startsOnFirstUse: Boolean = false,
        /** Профили из подписки действительно загрузились. */
        val serversImported: Int = 0,
        /** На бэкенде настроена группа поддержки — чат можно показывать. */
        val supportEnabled: Boolean = false,
        /** На бэкенде настроено продление — раздел «Подписка» можно показывать. */
        val renewEnabled: Boolean = false,
        /** Версия правил маршрутизации на сервере. */
        val routingVersion: Int = 0,
        /** Условия триала для кнопки «получить N дней». */
        val trialDays: Int = 0,
        val trialGb: Int = 0,
        /** Номер аккаунта, «4821 0937 5512 6604». Пусто — не вошли. */
        val accountNumber: String = "",
        /** Человек подтвердил, что сохранил номер. */
        val accountConfirmed: Boolean = true,
    )

    /** Ответ бэкенда. Поля разбирает Gson, поэтому имена совпадают с JSON. */
    private data class StartResponse(
        val status: String? = null,
        val subscription_url: String? = null,
        val subscription_name: String? = null,
        val username: String? = null,
        val days_left: Int? = null,
        val starts_on_first_use: Boolean = false,
        val support_enabled: Boolean = false,
        val renew_enabled: Boolean = false,
        val routing_version: Int = 0,
        val trial_days: Int = 0,
        val trial_gb: Int = 0,
        val account_number: String? = null,
        val confirmed: Boolean = true,
    )

    /** Ответ на вход: токен сессии и номер аккаунта. */
    private data class SessionResponse(
        val status: String? = null,
        val token: String? = null,
        val account_number: String? = null,
        val confirmed: Boolean = true,
        val message: String? = null,
    )

    private const val ME_PATH = "/api/v1/me"
    private const val TRIAL_PATH = "/api/v1/account/trial"
    private const val CREATE_PATH = "/api/v1/account/create"
    private const val LOGIN_PATH = "/api/v1/account/login"
    private const val CONFIRM_PATH = "/api/v1/account/confirm"
    private const val MIGRATE_PATH = "/api/v1/session/migrate"
    private const val LOGOUT_PATH = "/api/v1/logout"
    private const val JSON_TIMEOUT_SECONDS = 20L

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(JSON_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(JSON_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Для чека — свои сроки ожидания.
     *
     * Обычный разговор с бэкендом укладывается в секунды, и ждать его
     * дольше незачем. Чек — это файл до полусотни мегабайт, и на
     * мобильном интернете он уезжает минутами; с общими сроками отправка
     * обрывалась бы на полпути, причём молча — человек видел бы «нет
     * связи» при живой связи.
     */
    private val uploadClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(JSON_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.MINUTES)
            .readTimeout(2, TimeUnit.MINUTES)
            .callTimeout(15, TimeUnit.MINUTES)
            .build()
    }

    /** Настроен ли бэкенд вообще. Пустой адрес — обычный клиент без него. */
    val isConfigured: Boolean get() = Brand.backendUrl.isNotBlank()

    // --- Адрес бэкенда -------------------------------------------------------

    /**
     * Домен, который назвал сам бэкенд (api_base в /api/v1/version), —
     * без пути приложения. Пусто — ходим туда, куда зашито в сборке.
     * Так бэкенд может переехать на новый домен без новой сборки.
     */
    private const val PREF_API_BASE = "pref_backend_api_base"

    /** Адрес бэкенда с путём приложения: https://m.t-forge.online/proxyvv. */
    val baseUrl: String
        get() {
            val brand = Brand.backendUrl.trimEnd('/')
            val domain = MmkvManager.decodeSettingsString(PREF_API_BASE, "").orEmpty()
            if (domain.isBlank()) return brand
            val appPath = brand.toHttpUrlOrNull()?.encodedPath.orEmpty().trimEnd('/')
            return domain.trimEnd('/') + appPath
        }

    /** Адрес, зашитый в сборку, — запасной путь, если новый домен не отвечает. */
    private val brandBaseUrl: String get() = Brand.backendUrl.trimEnd('/')

    /**
     * Запоминает домен, названный бэкендом. Только https и только
     * домен целиком: из-за опечатки в настройках приложение не должно
     * уйти по адресу, где его никто не ждёт.
     */
    private fun rememberApiBase(apiBase: String?) {
        val value = apiBase.orEmpty().trim().trimEnd('/')
        if (value.isBlank()) return
        val url = value.toHttpUrlOrNull()
        if (url == null || url.scheme != "https" || url.encodedPath.trimEnd('/').isNotEmpty()) {
            LogUtil.w(AppConfig.TAG, "Бэкенд назвал негодный адрес: $value")
            return
        }
        val domain = "https://${url.host}" + if (url.port != 443) ":${url.port}" else ""
        if (domain != MmkvManager.decodeSettingsString(PREF_API_BASE, "")) {
            LogUtil.i(AppConfig.TAG, "Бэкенд теперь по адресу $domain")
            MmkvManager.encodeSettings(PREF_API_BASE, domain)
        }
    }

    // --- Что настроено на бэкенде ------------------------------------------

    /**
     * Есть ли чат поддержки и раздел «Подписка» — по последнему ответу
     * бэкенда, сохранённому на устройстве.
     *
     * Раньше это жило только в памяти и при каждом запуске начиналось с
     * «нет»: пока /start не ответил (до двадцати секунд) или если он не
     * ответил вовсе, «Поддержка» в настройках вела в Telegram, а потом —
     * в чат. Теперь экран сразу знает, что было в прошлый раз.
     *
     * Чат до первого ответа считаем включённым: он настроен у всех наших
     * приложений, а Telegram — запасной путь на случай, когда бэкенд прямо
     * сказал, что чата нет.
     */
    private const val PREF_SUPPORT_ENABLED = "pref_backend_support_enabled"
    private const val PREF_RENEW_ENABLED = "pref_backend_renew_enabled"

    val supportEnabledCached: Boolean
        get() = MmkvManager.decodeSettingsBool(PREF_SUPPORT_ENABLED, isConfigured)

    val renewEnabledCached: Boolean
        get() = MmkvManager.decodeSettingsBool(PREF_RENEW_ENABLED, false)

    /**
     * Android ID — то, чем устройство представлялось бэкенду до аккаунтов.
     *
     * Теперь он нужен только один раз: обменять его на сессию в аккаунте
     * прежней подписки (см. ensureSession). На части прошивок возвращает
     * пустую строку — тогда обменивать нечего.
     */
    @SuppressLint("HardwareIds")
    fun deviceId(context: Context): String =
        runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()

    // --- Аккаунт и сессия ----------------------------------------------------

    /** Токен сессии. Пусто — устройство не вошло в аккаунт. */
    private const val PREF_SESSION_TOKEN = "pref_account_session_token"

    /** Номер аккаунта — для «Настроек»: показать его можно и без сети. */
    private const val PREF_ACCOUNT_NUMBER = "pref_account_number"

    /**
     * Android ID уже обменян на сессию (или обменивать было нечего).
     *
     * Обмен — один раз: после выхода из аккаунта приложение не должно
     * войти обратно само, по старому ID.
     */
    private const val PREF_MIGRATED = "pref_account_migrated"

    private var sessionToken: String
        get() = MmkvManager.decodeSettingsString(PREF_SESSION_TOKEN, "").orEmpty()
        set(value) {
            MmkvManager.encodeSettings(PREF_SESSION_TOKEN, value)
        }

    private var migrated: Boolean
        get() = MmkvManager.decodeSettingsBool(PREF_MIGRATED, false)
        set(value) {
            MmkvManager.encodeSettings(PREF_MIGRATED, value)
        }

    /** Вошло ли устройство в аккаунт. */
    val hasSession: Boolean get() = sessionToken.isNotBlank()

    /** Номер аккаунта, каким его прислал бэкенд. Пусто — не вошли. */
    val accountNumber: String
        get() = if (hasSession) MmkvManager.decodeSettingsString(PREF_ACCOUNT_NUMBER, "").orEmpty() else ""

    /**
     * Аккаунт сменился на другом экране: вошли, вышли. Главный экран,
     * вернувшись, спрашивает бэкенд заново — иначе показывал бы прежнюю
     * подписку до следующего запуска.
     */
    @Volatile
    private var accountChanged = false

    fun consumeAccountChanged(): Boolean = accountChanged.also { accountChanged = false }

    private fun openSession(answer: SessionResponse): Boolean {
        val token = answer.token
        if (answer.status != "ok" || token.isNullOrBlank()) return false
        sessionToken = token
        migrated = true
        answer.account_number?.let { MmkvManager.encodeSettings(PREF_ACCOUNT_NUMBER, it) }
        accountChanged = true
        return true
    }

    /**
     * Сессии больше нет: вышли сами или устройство отвязали в кабинете.
     * Нашу подписку убираем вместе с ней — иначе серверы аккаунта
     * оставались бы на устройстве, которое из него вышло.
     */
    private fun closeSession() {
        sessionToken = ""
        MmkvManager.encodeSettings(PREF_ACCOUNT_NUMBER, "")
        val ours = ourSubscriptionUrl
        if (ours.isNotBlank()) {
            MmkvManager.decodeSubscriptions()
                .filter { it.subscription.url.trimEnd('/') == ours.trimEnd('/') }
                .forEach { MmkvManager.removeSubscription(it.guid) }
            ourSubscriptionUrl = ""
        }
        accountChanged = true
    }

    /** Что устройство сообщает о себе: для списка устройств в кабинете. */
    private fun deviceFields(): Map<String, Any?> = mapOf(
        "platform" to "android",
        "device_name" to listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" "),
        "app_version" to BuildConfig.VERSION_NAME,
        "app_package" to BuildConfig.APPLICATION_ID,
        "device_brand" to Build.BRAND,
        "device_model" to Build.MODEL,
        "android_release" to Build.VERSION.RELEASE,
        "android_sdk" to Build.VERSION.SDK_INT,
        "locale" to Locale.getDefault().toLanguageTag(),
        "timezone" to TimeZone.getDefault().id,
    )

    private enum class SessionState { READY, NONE, OFFLINE }

    /**
     * Есть ли сессия, а если нет — не обменять ли на неё Android ID.
     *
     * Обмен нужен один раз: устройство, у которого при прежней версии
     * была подписка, садится в аккаунт этой подписки. Бэкенд устройства
     * не знает — значит, приложение ставят впервые, и входить не во что.
     */
    private fun ensureSession(context: Context): SessionState {
        if (hasSession) return SessionState.READY
        if (migrated) return SessionState.NONE

        val androidId = deviceId(context)
        if (androidId.isBlank()) {
            migrated = true
            return SessionState.NONE
        }

        val answer = post(MIGRATE_PATH, deviceFields() + ("android_id" to androidId), SessionResponse::class.java)
            ?: return SessionState.OFFLINE
        if (openSession(answer)) {
            LogUtil.i(AppConfig.TAG, "Android ID обменян на сессию аккаунта")
            return SessionState.READY
        }
        if (answer.status == "not_found") {
            migrated = true
            return SessionState.NONE
        }
        // Бэкенд занят или аккаунты выключены — попробуем при следующем запуске.
        LogUtil.w(AppConfig.TAG, "Обмен Android ID не удался: ${answer.status}")
        return SessionState.OFFLINE
    }

    /**
     * Спрашивает подписку и, если она есть, заводит её в приложении.
     *
     * Вызывается при каждом запуске, а не только при первом: ответ заодно
     * говорит, не истёк ли срок, и подтягивает новые серверы.
     *
     * @param claim человек согласился на пробный период. Аккаунта нет —
     *              он заводится здесь же: номер приходит в ответе.
     */
    suspend fun provision(context: Context, claim: Boolean = false): Result =
        withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext Result(Status.UNAVAILABLE)

        when (ensureSession(context)) {
            SessionState.OFFLINE -> return@withContext Result(Status.NETWORK_ERROR)
            SessionState.NONE -> {
                if (!claim) return@withContext Result(Status.NEEDS_ACCOUNT)
                val created = post(CREATE_PATH, deviceFields(), SessionResponse::class.java)
                    ?: return@withContext Result(Status.NETWORK_ERROR)
                if (!openSession(created)) {
                    LogUtil.w(AppConfig.TAG, "Аккаунт не создан: ${created.status} ${created.message}")
                    return@withContext Result(Status.NETWORK_ERROR)
                }
                LogUtil.i(AppConfig.TAG, "Создан аккаунт")
            }
            SessionState.READY -> Unit
        }

        // Состояние подписки — тем же запросом, что отмечает заход
        // устройства: сведения о нём бэкенд берёт из тела.
        val answer = authorizedPost(if (claim) TRIAL_PATH else ME_PATH, deviceFields(), StartResponse::class.java)
            ?: return@withContext Result(Status.NETWORK_ERROR)

        if (answer.status == "unauthorized") {
            LogUtil.i(AppConfig.TAG, "Сессия закрыта на сервере — выходим из аккаунта")
            closeSession()
            return@withContext Result(Status.NEEDS_ACCOUNT)
        }

        val status = when (answer.status) {
            "active" -> Status.ACTIVE
            "expired" -> Status.EXPIRED
            "limited" -> Status.LIMITED
            "disabled" -> Status.DISABLED
            "offer" -> Status.OFFER
            "rate_limited" -> Status.RATE_LIMITED
            "unavailable" -> Status.UNAVAILABLE
            else -> Status.NETWORK_ERROR
        }

        if (status != Status.NETWORK_ERROR) {
            MmkvManager.encodeSettings(PREF_SUPPORT_ENABLED, answer.support_enabled)
            MmkvManager.encodeSettings(PREF_RENEW_ENABLED, answer.renew_enabled)
            answer.account_number?.takeIf { it.isNotBlank() }
                ?.let { MmkvManager.encodeSettings(PREF_ACCOUNT_NUMBER, it) }
        }

        val url = answer.subscription_url.orEmpty()
        // Подписку заводим и для истёкшей: когда человек продлит, адрес
        // остаётся тем же, и приложение подхватит серверы само.
        val imported = if (url.isNotBlank()) {
            syncSubscription(url, answer.subscription_name ?: Brand.name)
        } else {
            0
        }

        Result(
            status = status,
            subscriptionUrl = url,
            daysLeft = answer.days_left,
            startsOnFirstUse = answer.starts_on_first_use,
            serversImported = imported,
            supportEnabled = answer.support_enabled,
            renewEnabled = answer.renew_enabled,
            routingVersion = answer.routing_version,
            trialDays = answer.trial_days,
            trialGb = answer.trial_gb,
            accountNumber = answer.account_number.orEmpty(),
            accountConfirmed = answer.confirmed,
        )
    }

    /** Чем закончился вход по номеру. */
    enum class LoginOutcome { OK, NOT_FOUND, TOO_MANY_ATTEMPTS, NETWORK_ERROR }

    /**
     * Вход по номеру аккаунта. Прежняя сессия, если была, заменяется:
     * так входят и в другой аккаунт. Подписку нового аккаунта заводит
     * следующий provision.
     */
    suspend fun login(number: String): LoginOutcome = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext LoginOutcome.NETWORK_ERROR

        val answer = post(LOGIN_PATH, deviceFields() + ("number" to number), SessionResponse::class.java)
            ?: return@withContext LoginOutcome.NETWORK_ERROR
        when {
            answer.status == "not_found" -> LoginOutcome.NOT_FOUND
            answer.status == "too_many_attempts" -> LoginOutcome.TOO_MANY_ATTEMPTS
            openSession(answer) -> LoginOutcome.OK
            else -> LoginOutcome.NETWORK_ERROR
        }
    }

    /** «Я сохранил номер»: больше его не навязываем. */
    suspend fun confirmNumber(): Boolean = withContext(Dispatchers.IO) {
        if (!hasSession) return@withContext false
        authorizedPost(CONFIRM_PATH, emptyMap(), StatusOnly::class.java)?.status == "ok"
    }

    /**
     * Выход из аккаунта на этом устройстве. Сессия закрывается и на
     * сервере, а если он не ответил — хотя бы здесь: выйти человек должен
     * и без сети.
     */
    suspend fun logout() = withContext(Dispatchers.IO) {
        if (!hasSession) return@withContext
        authorizedPost(LOGOUT_PATH, emptyMap(), StatusOnly::class.java)
        closeSession()
        LogUtil.i(AppConfig.TAG, "Вышли из аккаунта")
    }

    /**
     * Номер аккаунта набран без опечатки: 16 цифр, последняя —
     * контрольная (Luhn). Проверяем здесь, чтобы опечатка не тратила
     * попытки входа, которые бэкенд считает.
     */
    fun isValidAccountNumber(raw: String): Boolean {
        val digits = raw.filter(Char::isDigit)
        if (digits.length != ACCOUNT_NUMBER_LENGTH || digits.length != raw.count { !it.isWhitespace() }) {
            return false
        }
        val sum = digits.reversed().mapIndexed { i, ch ->
            val d = ch - '0'
            if (i % 2 == 1) (d * 2).let { if (it > 9) it - 9 else it } else d
        }.sum()
        return sum % 10 == 0
    }

    const val ACCOUNT_NUMBER_LENGTH = 16

    /**
     * Кто спрашивает — для чата, продления и push.
     *
     * С сессией — никто в теле: токен едет заголовком. Без неё, пока
     * Android ID ещё не обменян (бэкенд не ответил на обмен), — по
     * старинке Android ID: бэкенд принимает и его. null — спрашивать
     * не от чьего имени.
     */
    private fun owner(context: Context): Map<String, Any?>? {
        if (hasSession) return emptyMap()
        if (migrated) return null
        val androidId = deviceId(context)
        return if (androidId.isBlank()) null else mapOf("android_id" to androidId)
    }

    private fun Request.Builder.withSession(): Request.Builder {
        val token = sessionToken
        if (token.isNotBlank()) header("Authorization", "Bearer $token")
        return this
    }

    private fun <T> authorizedPost(path: String, payload: Map<String, Any?>, cls: Class<T>): T? {
        val request = Request.Builder()
            .url(baseUrl + path)
            .withSession()
            .post(JsonUtil.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()
        return call(request, cls)
    }

    /**
     * Держит подписку в приложении ровно одной записью.
     *
     * Ищем по адресу, а не заводим каждый раз новую: при каждом запуске
     * иначе копился бы список одинаковых подписок. Возвращает, сколько
     * профилей загрузилось.
     */
    /**
     * Адрес подписки, выданный нам бэкендом в прошлый раз.
     *
     * Нужен, чтобы отличать свою подписку от добавленных руками: когда
     * бэкенд выдаёт другой адрес, прежнюю свою мы убираем, а чужие
     * оставляем в покое.
     */
    private const val PREF_SUB_URL = "pref_mobile_subscription_url"

    private var ourSubscriptionUrl: String
        get() = MmkvManager.decodeSettingsString(PREF_SUB_URL, "").orEmpty()
        set(value) {
            MmkvManager.encodeSettings(PREF_SUB_URL, value)
        }

    private fun syncSubscription(url: String, remarks: String): Int {
        // Подписка без адреса бесполезна и только мешает: обновление её
        // пропускает, а выбранной группой она быть может. Такие убираем.
        MmkvManager.decodeSubscriptions()
            .filter { it.subscription.url.isBlank() }
            .forEach { stale ->
                LogUtil.i(AppConfig.TAG, "Убрана подписка без адреса: ${stale.guid}")
                MmkvManager.removeSubscription(stale.guid)
            }

        // Прежнюю нашу подписку убираем, если бэкенд выдал другой адрес.
        // Так бывает при входе по коду: подписка становится общей, и
        // ключ может смениться на чужой — с более долгим сроком. Без
        // этого в списке оставались бы две группы, и какая из них живая,
        // человеку пришлось бы угадывать. Чужие подписки, добавленные
        // руками, не трогаем — помним ровно свой прошлый адрес.
        val previous = ourSubscriptionUrl
        if (previous.isNotBlank() && previous.trimEnd('/') != url.trimEnd('/')) {
            MmkvManager.decodeSubscriptions()
                .filter { it.subscription.url.trimEnd('/') == previous.trimEnd('/') }
                .forEach { stale ->
                    LogUtil.i(AppConfig.TAG, "Подписка сменилась, убрана прежняя: ${stale.guid}")
                    MmkvManager.removeSubscription(stale.guid)
                }
        }
        ourSubscriptionUrl = url

        val existing = MmkvManager.decodeSubscriptions().firstOrNull {
            it.subscription.url.trimEnd('/') == url.trimEnd('/')
        }

        val cache = existing ?: run {
            val guid = Utils.getUuid()
            MmkvManager.encodeSubscription(
                guid,
                SubscriptionItem(
                    remarks = remarks,
                    url = url,
                    enabled = true,
                    // Обновляется само раз в сутки: иначе смена серверов
                    // на нашей стороне доезжала бы только после запуска.
                    autoUpdate = true,
                ),
            )
            MmkvManager.decodeSubscriptions().first { it.guid == guid }
        }

        val result = AngConfigManager.updateConfigViaSub(cache)
        LogUtil.i(
            AppConfig.TAG,
            "Подписка обновлена: профилей ${result.configCount}, ошибок ${result.failureCount}"
        )
        return result.configCount
    }

    // --- Чат поддержки -------------------------------------------------------

    /** Сообщение переписки. Токенов бота в приложении нет: всё через бэкенд. */
    data class SupportMessage(
        val id: Long = 0,
        val direction: String = "",
        val text: String = "",
        val author: String = "",
        val created_at: String = "",
    ) {
        /** Ответ поддержки, а не собственное сообщение. */
        val fromSupport: Boolean get() = direction == "out"
    }

    data class SupportPage(
        val messages: List<SupportMessage> = emptyList(),
        /** Выше есть ещё история — её подгружает прокрутка вверх. */
        val hasMore: Boolean = false,
    )

    private data class MessagesResponse(
        val status: String? = null,
        val messages: List<SupportMessage>? = null,
        val has_more: Boolean = false,
    )

    private data class SendResponse(
        val status: String? = null,
        val message: SupportMessage? = null,
    )

    private const val SUPPORT_MESSAGES_PATH = "/api/v1/support/messages"
    private const val SUPPORT_SEND_PATH = "/api/v1/support/send"

    /**
     * Загружает переписку.
     *
     * Без параметров — хвост диалога: чат открывается на последних
     * сообщениях, а не тянет всю историю. `after` опрашивает новое,
     * `before` подгружает страницу выше.
     */
    suspend fun supportMessages(
        context: Context,
        after: Long? = null,
        before: Long? = null,
        limit: Int = 30,
    ): SupportPage? = withContext(Dispatchers.IO) {
        val owner = owner(context)
        if (!isConfigured || owner == null) return@withContext null

        val url = StringBuilder(baseUrl)
            .append(SUPPORT_MESSAGES_PATH)
            .append("?limit=").append(limit)
        owner["android_id"]?.let { url.append("&android_id=").append(it) }
        if (after != null) url.append("&after=").append(after)
        if (before != null) url.append("&before=").append(before)

        val answer = get(url.toString(), MessagesResponse::class.java)
            ?: return@withContext null

        SupportPage(answer.messages.orEmpty(), answer.has_more)
    }

    /** Отправляет сообщение в поддержку. null — не дошло. */
    suspend fun sendSupport(context: Context, text: String): SupportMessage? =
        withContext(Dispatchers.IO) {
            val owner = owner(context)
            if (!isConfigured || owner == null) return@withContext null

            authorizedPost(SUPPORT_SEND_PATH, owner + ("text" to text), SendResponse::class.java)?.message
        }

    private fun <T> get(url: String, cls: Class<T>): T? =
        call(Request.Builder().url(url).withSession().get().build(), cls)

    private fun <T> call(
        request: Request,
        cls: Class<T>,
        with: OkHttpClient = client,
    ): T? = try {
        with.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) null else JsonUtil.fromJson(body, cls)
        }
    } catch (e: Exception) {
        LogUtil.w(AppConfig.TAG, "Бэкенд недоступен: ${e.message}")
        null
    }

    // --- Вход на другом устройстве -------------------------------------------

    /**
     * Код для входа: шесть цифр, срок, после которого он протухнет, и
     * секрет ожидания — с ним одним бэкенд отдаст сессию, когда код
     * отсканируют. Секрет знает только это устройство.
     */
    data class LinkCode(
        val code: String = "",
        val ttlSeconds: Int = 0,
        val waitSecret: String = "",
    )

    private data class LinkCodeResponse(
        val status: String? = null,
        val code: String? = null,
        val wait_secret: String? = null,
        val ttl_seconds: Int = 0,
        val message: String? = null,
    )

    private const val CODE_REQUEST_PATH = "/api/v1/code/request"
    private const val CODE_GRANT_PATH = "/api/v1/code/grant"
    private const val CODE_STATUS_PATH = "/api/v1/code/status"

    /**
     * Просит код, чтобы войти на этом устройстве.
     *
     * Аккаунта для этого иметь не нужно: код показывают тому, кто уже
     * вошёл, и он впускает. null — не вышло.
     */
    suspend fun requestJoinCode(): LinkCode? = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext null

        val answer = post(CODE_REQUEST_PATH, emptyMap(), LinkCodeResponse::class.java)
            ?: return@withContext null
        val code = answer.code
        val secret = answer.wait_secret
        if (answer.status != "ok" || code.isNullOrBlank() || secret.isNullOrBlank()) {
            LogUtil.w(AppConfig.TAG, "Код входа не выдан: ${answer.status} ${answer.message}")
            return@withContext null
        }

        LinkCode(code, answer.ttl_seconds, secret)
    }

    /**
     * Впускает того, чей код отсканировали, в свой аккаунт.
     *
     * ACTIVE — впустили; UNAVAILABLE — делиться нечем (не вошли или у
     * аккаунта нет подписки); BAD_CODE — код не подошёл.
     */
    suspend fun grantLinkCode(context: Context, code: String): Result = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext Result(Status.NETWORK_ERROR)
        when (ensureSession(context)) {
            SessionState.OFFLINE -> return@withContext Result(Status.NETWORK_ERROR)
            SessionState.NONE -> return@withContext Result(Status.UNAVAILABLE)
            SessionState.READY -> Unit
        }

        val answer = authorizedPost(CODE_GRANT_PATH, mapOf("code" to code), StatusOnly::class.java)
            ?: return@withContext Result(Status.NETWORK_ERROR)
        when (answer.status) {
            "ok" -> Result(Status.ACTIVE)
            "no_subscription" -> Result(Status.UNAVAILABLE)
            "unauthorized" -> {
                closeSession()
                Result(Status.UNAVAILABLE)
            }
            else -> Result(Status.BAD_CODE)
        }
    }

    /**
     * Спрашивает, впустили ли уже по показанному коду.
     *
     * Пока никто не отсканировал, бэкенд отвечает «ждём» — для
     * приложения это PENDING, и опрос продолжается. Впустили — приходит
     * сессия в аккаунте впустившего, и подписка заводится тут же.
     */
    suspend fun pollJoinStatus(context: Context, link: LinkCode): Result = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext Result(Status.NETWORK_ERROR)

        val payload = deviceFields() + mapOf("code" to link.code, "wait_secret" to link.waitSecret)
        val answer = post(CODE_STATUS_PATH, payload, SessionResponse::class.java)
            ?: return@withContext Result(Status.NETWORK_ERROR)
        when {
            answer.status == "pending" -> Result(Status.PENDING)
            openSession(answer) -> provision(context)
            else -> Result(Status.BAD_CODE)
        }
    }

    // --- Версия приложения ---------------------------------------------------

    private const val VERSION_PATH = "/api/v1/version"

    /** Последняя выложенная сборка — как её описывает бэкенд. */
    data class Release(
        val version_name: String? = null,
        val version_code: Long = 0,
        /** Когда выложена, ISO 8601 с часовым поясом. */
        val released_at: String? = null,
        val apk_url: String? = null,
        val apk_size: Long = 0,
        val apk_sha256: String? = null,
    )

    private data class VersionResponse(
        val status: String? = null,
        val api_base: String? = null,
        val latest: Release? = null,
    )

    /**
     * Спрашивает последнюю версию и заодно — на какой домен ходить.
     *
     * Сначала туда, куда ходим сейчас; не ответил, а адрес не тот, что в
     * сборке, — туда, что в сборке. Без этого неудачный api_base отрезал
     * бы приложение от бэкенда насовсем: исправить его можно было бы
     * только этим же запросом. null — не достучались никуда.
     */
    suspend fun latestRelease(): Release? = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext null

        val answer = get(baseUrl + VERSION_PATH, VersionResponse::class.java)
            ?: brandBaseUrl.takeIf { it != baseUrl }
                ?.let { get(it + VERSION_PATH, VersionResponse::class.java) }
            ?: return@withContext null

        if (answer.status != "ok") return@withContext null
        rememberApiBase(answer.api_base)
        answer.latest
    }

    /** Что зашито в QR: код, который сканирует сторона с подпиской. */
    fun joinQrPayload(code: String): String =
        "${BuildConfig.BRAND_URL_SCHEME}://join?c=$code"

    // --- Правила маршрутизации -----------------------------------------------

    /**
     * Что приходит с сервера: списки правил и их версия.
     *
     * Формат совпадает с наборами правил внутри клиента, поэтому элементы
     * применяются как есть — переводить ничего не нужно.
     */
    private data class RoutingResponse(
        val version: Int = 0,
        val settings: RoutingSettings? = null,
        val ru_direct: List<RulesetItem>? = null,
        val common: List<RulesetItem>? = null,
    )

    /**
     * Настройки ядра, без которых правила работают наполовину.
     *
     * domain_strategy — как ядро сопоставляет правила. При «AsIs» оно
     * сравнивает с правилом ровно то, что увидело: для обычного запроса
     * это имя сайта, поэтому правила по адресам (geoip:ru) не совпадают
     * никогда и живут впустую. «IPIfNonMatch» заставляет ядро выяснить
     * адрес и сравнить ещё и его — тогда напрямую уходит всё российское,
     * а не только перечисленное руками.
     *
     * direct_dns — чей ответ ядро спрашивает для сайтов, идущих мимо
     * VPN. Умолчание v2rayNG — китайский резолвер; российские сайты он
     * отдаёт медленно, а то и не отдаёт вовсе, и сайт просто не
     * открывается, хотя маршрут выбран верный.
     */
    private data class RoutingSettings(
        val domain_strategy: String? = null,
        val direct_dns: List<String>? = null,
    )

    /** Значения, которые понимает ядро. Чужое сломало бы маршрутизацию. */
    private val ALLOWED_DOMAIN_STRATEGY = setOf("AsIs", "IPIfNonMatch", "IPOnDemand")

    private const val ROUTING_PATH = "/api/v1/routing"

    /** Настройка «российские сервисы напрямую». По умолчанию включена. */
    private const val PREF_RU_DIRECT = "pref_mobile_ru_direct"

    var ruDirectEnabled: Boolean
        get() = MmkvManager.decodeSettingsBool(PREF_RU_DIRECT, true)
        set(value) {
            MmkvManager.encodeSettings(PREF_RU_DIRECT, value)
        }

    /** Версия правил, которые сейчас применены. 0 — ни разу не применяли. */
    private const val PREF_ROUTING_VERSION = "pref_mobile_routing_version"

    private var appliedRoutingVersion: Int
        get() = MmkvManager.decodeSettingsString(PREF_ROUTING_VERSION, "0")?.toIntOrNull() ?: 0
        set(value) {
            MmkvManager.encodeSettings(PREF_ROUTING_VERSION, value.toString())
        }

    /**
     * Забирает правила с сервера и применяет их.
     *
     * Порядок решает: выигрывает первое совпавшее правило, поэтому
     * «напрямую» идёт перед «всё остальное через VPN». Если тумблер
     * выключен, первый список просто не добавляется — и всё уходит
     * через сервер.
     *
     * @param force применить, даже если версия та же (нажали «обновить»
     *              или переключили тумблер).
     * @return true, если правила применены.
     */
    suspend fun syncRouting(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext false

        val answer = get(baseUrl + ROUTING_PATH, RoutingResponse::class.java)
            ?: return@withContext false

        if (!force && answer.version == appliedRoutingVersion) {
            return@withContext false
        }

        val rules = buildList {
            if (ruDirectEnabled) addAll(answer.ru_direct.orEmpty())
            addAll(answer.common.orEmpty())
        }.mapNotNull(::sanitizeRule)
        if (rules.isEmpty()) return@withContext false

        val applied = SettingsManager.resetRoutingRulesets(JsonUtil.toJson(rules))
        if (applied) {
            applyRoutingSettings(answer.settings)
            appliedRoutingVersion = answer.version
            LogUtil.i(
                AppConfig.TAG,
                "Правила маршрутизации применены: версия ${answer.version}, " +
                    "правил ${rules.size}, российские напрямую — $ruDirectEnabled"
            )
        }
        applied
    }

    /**
     * Применяет настройки ядра, пришедшие вместе с правилами.
     *
     * Непонятое значение молча пропускаем: правила важнее, и ломать из-за
     * опечатки в файле всю маршрутизацию незачем.
     */
    private fun applyRoutingSettings(settings: RoutingSettings?) {
        if (settings == null) return

        settings.domain_strategy?.let { strategy ->
            if (strategy in ALLOWED_DOMAIN_STRATEGY) {
                MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY, strategy)
            } else {
                LogUtil.w(AppConfig.TAG, "Неизвестный способ сопоставления правил: $strategy")
            }
        }

        // Ядро понимает либо чистый адрес, либо запись вида https://…/dns-query,
        // поэтому всё остальное отсеиваем здесь же.
        settings.direct_dns
            ?.filter { Utils.isPureIpAddress(it) || Utils.isCoreDNSAddress(it) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { MmkvManager.encodeSettings(AppConfig.PREF_DOMESTIC_DNS, it.joinToString(",")) }
    }

    /**
     * Справочники, на которые можно ссылаться в правилах.
     *
     * Ссылка на отсутствующую категорию роняет ядро целиком: VPN
     * перестаёт подниматься, и не работает вообще ничего — ни то, что
     * должно идти напрямую, ни то, что через сервер. Поэтому пропускаем
     * только те, что заведомо есть в geosite.dat и geoip.dat, идущих с
     * приложением (внутри libv2ray.aar); список сверен с их содержимым.
     *
     * Про category-ru стоит знать отдельно: вопреки названию, это не «все
     * российские сайты», а разрозненный набор примерно на тысячу адресов,
     * где нет ни госуслуг, ни банков, ни магазинов. Крупные сервисы лежат
     * по отраслевым категориям ниже, а весь домен .ru целиком — в tld-ru.
     */
    private val ALLOWED_GEO = setOf(
        "geosite:private",
        "geosite:tld-ru",
        "geosite:category-ru",
        "geosite:category-gov-ru",
        "geosite:category-bank-ru",
        "geosite:category-retail-ru",
        "geosite:category-ecommerce-ru",
        "geosite:category-medicine-ru",
        "geosite:category-travel-ru",
        "geosite:category-media-ru",
        "geosite:category-entertainment-ru",
        "geosite:category-education-ru",
        "geosite:category-tech-media-ru",
        "geosite:category-forums-ru",
        "geoip:private",
        "geoip:ru",
    )

    /**
     * Выбрасывает из правила ссылки на незнакомые справочники.
     * Правило, от которого ничего не осталось, отбрасывается целиком.
     */
    private fun sanitizeRule(rule: RulesetItem): RulesetItem? {
        fun keep(values: List<String>?): List<String>? {
            if (values == null) return null
            val safe = values.filter { value ->
                val isGeo = value.startsWith("geosite:") || value.startsWith("geoip:")
                if (isGeo && value !in ALLOWED_GEO) {
                    LogUtil.w(AppConfig.TAG, "Правило ссылается на неизвестный справочник: $value")
                    false
                } else {
                    true
                }
            }
            return safe.ifEmpty { null }
        }

        val domain = keep(rule.domain)
        val ip = keep(rule.ip)

        // Правило без единого условия совпало бы со ВСЕМ подряд.
        val hasCondition = domain != null || ip != null ||
            !rule.protocol.isNullOrEmpty() || !rule.process.isNullOrEmpty() ||
            !rule.port.isNullOrBlank() || !rule.network.isNullOrBlank()
        if (!hasCondition) return null

        return rule.copy(domain = domain, ip = ip)
    }

    // --- Продление -----------------------------------------------------------
    //
    // Устроено как в телеграм-боте: тариф, оплата, чек. Решение по чеку
    // принимает человек на той стороне, приложение только показывает, что
    // с заявкой происходит. Панель отсюда не трогается вовсе.

    /** Тариф: сколько заплатить и что за это будет. */
    data class Tier(
        val index: Int = 0,
        val amount: Int = 0,
        val days: Int = 0,
        val gb: Int = 0,
        val emoji: String = "",
    )

    /** Подписка глазами панели: срок и трафик. */
    data class SubscriptionInfo(
        val username: String = "",
        val status: String = "",
        val days_left: Int? = null,
        val data_limit: Long = 0,
        val used_traffic: Long = 0,
        val starts_on_first_use: Boolean = false,
    )

    /** Последняя заявка на продление и что с ней стало. */
    data class RenewRequest(
        val id: Long = 0,
        val amount: Int? = null,
        val days: Int? = null,
        val gb: Int? = null,
        val status: String = "",
        /** Чек уже у тех, кто его разбирает. */
        val delivered: Boolean = false,
    ) {
        /** Решения ещё нет: второй чек поверх этого слать нечего. */
        val isOpen: Boolean get() = status in OPEN_STATUSES

        /** Заявку применили — срок вырос. */
        val isApproved: Boolean get() = status == "approved" || status == "auto_approved"
    }

    /** Весь экран подписки одним ответом. */
    data class RenewState(
        val status: String = "",
        val renew_enabled: Boolean = false,
        val tiers: List<Tier> = emptyList(),
        val allow_custom: Boolean = false,
        val pay_url: String = "",
        val requisites: String = "",
        val auto_approve_minutes: Int = 0,
        val subscription: SubscriptionInfo? = null,
        val request: RenewRequest? = null,
        val message: String = "",
    )

    private data class ReceiptResponse(
        val status: String? = null,
        val message: String? = null,
        val request: RenewRequest? = null,
    )

    private const val PUSH_PATH = "/api/v1/push/register"
    private const val RENEW_PATH = "/api/v1/renew"
    private const val RENEW_RECEIPT_PATH = "/api/v1/renew/receipt"

    /** Статусы заявки, при которых решение ещё не принято. */
    private val OPEN_STATUSES = setOf("pending", "processing", "manual", "failed")

    /**
     * Сколько весит чек, который мы готовы отправить. Столько же
     * принимает бэкенд: больше полусотни мегабайт не возьмёт Telegram,
     * которым чек и уходит админам.
     */
    const val RECEIPT_LIMIT_BYTES = 50 * 1024 * 1024

    /**
     * Сколько файла переводим в base64 за раз. Кратно трём: тогда куски
     * можно просто склеивать — выравнивающих знаков внутри не возникает.
     */
    private const val BASE64_CHUNK_BYTES = 3 * 64 * 1024

    /**
     * Сообщает бэкенду адрес этого телефона для push-уведомлений.
     *
     * Бэкенд отвечает отказом, если устройства он ещё не знает, — так
     * бывает при самом первом запуске, до выдачи подписки. Ничего
     * страшного: адрес уедет при следующем запуске.
     */
    suspend fun registerPush(context: Context, token: String): Boolean = withContext(Dispatchers.IO) {
        val owner = owner(context)
        if (!isConfigured || owner == null) return@withContext false

        val answer = authorizedPost(PUSH_PATH, owner + ("token" to token), StatusOnly::class.java)
        answer?.status == "ok"
    }

    private data class StatusOnly(val status: String? = null)

    /**
     * Экран подписки: срок, трафик, тарифы, судьба последнего чека.
     * null — не достучались или бэкенд ответил отказом.
     */
    suspend fun renewState(context: Context): RenewState? = withContext(Dispatchers.IO) {
        val owner = owner(context)
        if (!isConfigured || owner == null) return@withContext null

        authorizedPost(RENEW_PATH, owner, RenewState::class.java)
            ?.takeIf { it.status == "ok" }
    }

    /**
     * Чек об оплате. Файл любой: снимок экрана, фотография, pdf из банка.
     *
     * Возвращает заведённую заявку либо причину отказа — её бэкенд
     * присылает обычным ответом, и показать её человеку должен тот, кто
     * вызвал: «предыдущий чек ещё не рассмотрен» выглядит иначе, чем
     * «нет связи».
     */
    suspend fun sendReceipt(
        context: Context,
        tier: Int,
        bytes: ByteArray,
        fileName: String,
    ): Pair<RenewRequest?, String?> = withContext(Dispatchers.IO) {
        val owner = owner(context)
        if (!isConfigured || owner == null) return@withContext null to null

        val request = Request.Builder()
            .url(baseUrl + RENEW_RECEIPT_PATH)
            .withSession()
            .post(receiptBody(owner["android_id"] as String?, tier, bytes, fileName))
            .build()

        val answer = call(request, ReceiptResponse::class.java, uploadClient)
            ?: return@withContext null to null

        if (answer.status == "ok") answer.request to null else null to answer.message
    }

    /**
     * Тело запроса с чеком.
     *
     * Файл едет внутри json, в base64, и переводим мы его кусками прямо
     * в сокет. Готовой строкой это было бы ещё треть сверху к весу
     * файла, да плюс её копия в самом запросе: на пятидесяти мегабайтах
     * телефон кончается раньше, чем отправка начинается.
     */
    private fun receiptBody(
        androidId: String?,
        tier: Int,
        bytes: ByteArray,
        fileName: String,
    ): RequestBody {
        // Имя файла — единственное, что пришло снаружи: экранируем его
        // тем же, чем собираем остальные запросы. Android ID — только у
        // устройства без сессии: с ней владельца называет токен.
        val owner = if (androidId != null) "\"android_id\":\"" + androidId + "\"," else ""
        val head = (
            "{" + owner + "\"tier\":" + tier +
                ",\"name\":" + JsonUtil.toJson(fileName) + ",\"image\":\""
            ).toByteArray()
        val tail = "\"}".toByteArray()

        return object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()

            // Длина известна заранее: base64 — это ровно четыре знака на
            // каждые три байта, с округлением вверх.
            override fun contentLength(): Long =
                head.size + tail.size + (bytes.size + 2L) / 3L * 4L

            override fun writeTo(sink: BufferedSink) {
                sink.write(head)
                var sent = 0
                while (sent < bytes.size) {
                    val size = minOf(BASE64_CHUNK_BYTES, bytes.size - sent)
                    sink.write(Base64.encode(bytes, sent, size, Base64.NO_WRAP))
                    sent += size
                }
                sink.write(tail)
            }
        }
    }

    private fun <T> post(path: String, payload: Map<String, Any?>, cls: Class<T>): T? {
        val request = Request.Builder()
            .url(baseUrl + path)
            .post(JsonUtil.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()

        // Тело разбираем и у неуспешного ответа: в нём лежит причина отказа.
        return call(request, cls)
    }
}
