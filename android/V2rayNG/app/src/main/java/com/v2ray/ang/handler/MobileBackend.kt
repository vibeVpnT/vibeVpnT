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
 * При запуске приложение называет свой Android ID и получает обратно
 * готовый адрес подписки — человеку ничего не нужно вводить руками.
 * Тот же ответ говорит, жива подписка или срок вышел.
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
    )

    private const val START_PATH = "/api/v1/start"
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

    /**
     * Android ID — то, чем устройство представляется бэкенду.
     *
     * Он переживает обновление приложения и сбрасывается только сбросом
     * телефона к заводским настройкам, поэтому подписка не теряется при
     * переустановке. На части прошивок возвращает пустую строку — тогда
     * спросить подписку не у кого.
     */
    @SuppressLint("HardwareIds")
    fun deviceId(context: Context): String =
        runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()

    /**
     * Спрашивает подписку и, если она есть, заводит её в приложении.
     *
     * Вызывается при каждом запуске, а не только при первом: ответ заодно
     * говорит, не истёк ли срок, и подтягивает новые серверы.
     */
    suspend fun provision(context: Context, claim: Boolean = false): Result =
        withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext Result(Status.UNAVAILABLE)

        val androidId = deviceId(context)
        if (androidId.isBlank()) {
            LogUtil.w(AppConfig.TAG, "Android ID недоступен — подписку спросить не у кого")
            return@withContext Result(Status.NETWORK_ERROR)
        }

        val answer = request(androidId, claim)
            ?: return@withContext Result(Status.NETWORK_ERROR)

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
        )
    }

    private fun request(androidId: String, claim: Boolean): StartResponse? {
        val payload = mapOf(
            "android_id" to androidId,
            // Без этого флага бэкенд только сообщает условия триала:
            // ключ создаётся лишь после согласия человека.
            "claim" to claim,
            "app_version" to BuildConfig.VERSION_NAME,
            "app_package" to BuildConfig.APPLICATION_ID,
            "device_brand" to Build.BRAND,
            "device_model" to Build.MODEL,
            "android_release" to Build.VERSION.RELEASE,
            "android_sdk" to Build.VERSION.SDK_INT,
            "locale" to Locale.getDefault().toLanguageTag(),
            "timezone" to TimeZone.getDefault().id,
        )

        val request = Request.Builder()
            .url(Brand.backendUrl.trimEnd('/') + START_PATH)
            .post(JsonUtil.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()

        // Разбираем тело и у неуспешного ответа: в нём лежит причина
        // отказа, а она нужна человеку на экране.
        return call(request, StartResponse::class.java)
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
        val androidId = deviceId(context)
        if (!isConfigured || androidId.isBlank()) return@withContext null

        val url = StringBuilder(Brand.backendUrl.trimEnd('/'))
            .append(SUPPORT_MESSAGES_PATH)
            .append("?android_id=").append(androidId)
            .append("&limit=").append(limit)
        if (after != null) url.append("&after=").append(after)
        if (before != null) url.append("&before=").append(before)

        val answer = get(url.toString(), MessagesResponse::class.java)
            ?: return@withContext null

        SupportPage(answer.messages.orEmpty(), answer.has_more)
    }

    /** Отправляет сообщение в поддержку. null — не дошло. */
    suspend fun sendSupport(context: Context, text: String): SupportMessage? =
        withContext(Dispatchers.IO) {
            val androidId = deviceId(context)
            if (!isConfigured || androidId.isBlank()) return@withContext null

            val body = JsonUtil.toJson(mapOf("android_id" to androidId, "text" to text))
            val request = Request.Builder()
                .url(Brand.backendUrl.trimEnd('/') + SUPPORT_SEND_PATH)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            call(request, SendResponse::class.java)?.message
        }

    private fun <T> get(url: String, cls: Class<T>): T? =
        call(Request.Builder().url(url).get().build(), cls)

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

    /** Код для входа: шесть цифр и срок, после которого он протухнет. */
    data class LinkCode(
        val code: String = "",
        val ttlSeconds: Int = 0,
    )

    private data class LinkCodeResponse(
        val status: String? = null,
        val code: String? = null,
        val ttl_seconds: Int = 0,
        val message: String? = null,
    )

    private const val LINK_REQUEST_PATH = "/api/v1/link/request"
    private const val LINK_GRANT_PATH = "/api/v1/link/grant"
    private const val LINK_STATUS_PATH = "/api/v1/link/status"

    /**
     * Просит код, чтобы войти на этом устройстве.
     *
     * Подписки для этого иметь не нужно: код показывают тому, у кого
     * она есть, и он её отдаёт. null — не вышло.
     */
    suspend fun requestJoinCode(context: Context): LinkCode? = withContext(Dispatchers.IO) {
        val androidId = deviceId(context)
        if (!isConfigured || androidId.isBlank()) return@withContext null

        // Сведения об устройстве шлём и здесь: по этому запросу бэкенд
        // заводит строку устройства, и дальше ему есть к чему привязать
        // подписку.
        val body = JsonUtil.toJson(
            mapOf(
                "android_id" to androidId,
                "app_version" to BuildConfig.VERSION_NAME,
                "device_brand" to Build.BRAND,
                "device_model" to Build.MODEL,
                "android_release" to Build.VERSION.RELEASE,
                "android_sdk" to Build.VERSION.SDK_INT,
            )
        )
        val request = Request.Builder()
            .url(Brand.backendUrl.trimEnd('/') + LINK_REQUEST_PATH)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val answer = call(request, LinkCodeResponse::class.java) ?: return@withContext null
        val code = answer.code
        if (answer.status != "ok" || code.isNullOrBlank()) {
            LogUtil.w(AppConfig.TAG, "Код входа не выдан: ${answer.status} ${answer.message}")
            return@withContext null
        }

        LinkCode(code, answer.ttl_seconds)
    }

    /**
     * Делится своей подпиской с тем, чей код отсканировали.
     *
     * Отвечает так же, как обычный запуск: если общей стала подписка
     * другой стороны — а так бывает, когда её срок длиннее, — это
     * устройство сразу переедет на неё.
     */
    suspend fun grantLinkCode(context: Context, code: String): Result =
        linkCall(context, LINK_GRANT_PATH, code, failure = Status.BAD_CODE)

    /**
     * Спрашивает, впустили ли уже по показанному коду.
     *
     * Пока никто не отсканировал, бэкенд отвечает «ждём» — для
     * приложения это PENDING, и опрос продолжается.
     */
    suspend fun pollJoinStatus(context: Context, code: String): Result =
        linkCall(context, LINK_STATUS_PATH, code, failure = Status.PENDING)

    /**
     * Общая часть обеих половин входа по коду.
     *
     * @param failure чем считать ответ без подписки: для сканирования
     *                это плохой код, для ожидания — «ещё не впустили».
     */
    private suspend fun linkCall(
        context: Context,
        path: String,
        code: String,
        failure: Status,
    ): Result = withContext(Dispatchers.IO) {
        val androidId = deviceId(context)
        if (!isConfigured || androidId.isBlank()) {
            return@withContext Result(Status.NETWORK_ERROR)
        }

        val payload = mapOf(
            "android_id" to androidId,
            "code" to code,
            "app_version" to BuildConfig.VERSION_NAME,
            "device_brand" to Build.BRAND,
            "device_model" to Build.MODEL,
            "android_release" to Build.VERSION.RELEASE,
            "android_sdk" to Build.VERSION.SDK_INT,
        )
        val request = Request.Builder()
            .url(Brand.backendUrl.trimEnd('/') + path)
            .post(JsonUtil.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()

        val answer = call(request, StartResponse::class.java)
            ?: return@withContext Result(Status.NETWORK_ERROR)

        if (answer.status != "active" && answer.subscription_url.isNullOrBlank()) {
            // «Делиться нечем» — не то же самое, что «код не подошёл»:
            // человеку надо объяснить разное.
            if (answer.status == "no_subscription") {
                return@withContext Result(Status.UNAVAILABLE)
            }
            return@withContext Result(failure)
        }

        val url = answer.subscription_url.orEmpty()
        val imported = if (url.isNotBlank()) {
            syncSubscription(url, answer.subscription_name ?: Brand.name)
        } else {
            0
        }

        Result(
            status = Status.ACTIVE,
            subscriptionUrl = url,
            daysLeft = answer.days_left,
            startsOnFirstUse = answer.starts_on_first_use,
            serversImported = imported,
            supportEnabled = answer.support_enabled,
        )
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

        val answer = get(Brand.backendUrl.trimEnd('/') + ROUTING_PATH, RoutingResponse::class.java)
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
        val androidId = deviceId(context)
        if (!isConfigured || androidId.isBlank()) return@withContext false

        val answer = post(
            PUSH_PATH,
            mapOf("android_id" to androidId, "token" to token),
            StatusOnly::class.java,
        )
        answer?.status == "ok"
    }

    private data class StatusOnly(val status: String? = null)

    /**
     * Экран подписки: срок, трафик, тарифы, судьба последнего чека.
     * null — не достучались или бэкенд ответил отказом.
     */
    suspend fun renewState(context: Context): RenewState? = withContext(Dispatchers.IO) {
        val androidId = deviceId(context)
        if (!isConfigured || androidId.isBlank()) return@withContext null

        post(RENEW_PATH, mapOf("android_id" to androidId), RenewState::class.java)
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
        val androidId = deviceId(context)
        if (!isConfigured || androidId.isBlank()) return@withContext null to null

        val request = Request.Builder()
            .url(Brand.backendUrl.trimEnd('/') + RENEW_RECEIPT_PATH)
            .post(receiptBody(androidId, tier, bytes, fileName))
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
        androidId: String,
        tier: Int,
        bytes: ByteArray,
        fileName: String,
    ): RequestBody {
        // Имя файла — единственное, что пришло снаружи: экранируем его
        // тем же, чем собираем остальные запросы.
        val head = (
            "{\"android_id\":\"" + androidId + "\",\"tier\":" + tier +
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
            .url(Brand.backendUrl.trimEnd('/') + path)
            .post(JsonUtil.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()

        // Тело разбираем и у неуспешного ответа: в нём лежит причина отказа.
        return call(request, cls)
    }
}
