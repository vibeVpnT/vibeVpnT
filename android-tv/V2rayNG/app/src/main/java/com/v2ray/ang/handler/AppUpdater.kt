package com.v2ray.ang.handler

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Обновление приложения из самого приложения.
 *
 * Какая сборка последняя, говорит наш бэкенд (MobileBackend.latestRelease);
 * сравниваем с той, что стоит. Новее — скачиваем apk, сверяем размер и
 * SHA-256 с тем, что назвал бэкенд, и отдаём системе на установку. Ставит
 * его сама Android: в первый раз она спросит разрешение ставить
 * приложения из этого источника.
 */
object AppUpdater {

    /** Доступное обновление — то, что показывает экран настроек. */
    data class Update(
        val versionName: String,
        /** Когда выложено, уже в виде для человека: «23.10.2026 12:17». */
        val releasedAt: String,
        val release: MobileBackend.Release,
    )

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.MINUTES)
            .build()
    }

    /**
     * versionCode той сборки, что стоит, — у PackageManager, а не из
     * BuildConfig: у apk свой код (versionCode * 10 + номер ABI), и
     * бэкенд называет именно его.
     */
    private fun installedVersionCode(context: Context): Long =
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
        }.getOrDefault(0L)

    /** Есть ли сборка новее установленной. null — нет или не узнали. */
    suspend fun check(context: Context): Update? {
        val release = MobileBackend.latestRelease() ?: return null
        val installed = installedVersionCode(context)
        if (release.version_code <= installed || release.apk_url.isNullOrBlank()) return null

        LogUtil.i(
            AppConfig.TAG,
            "Доступна версия ${release.version_name} (${release.version_code}), стоит $installed",
        )
        return Update(
            versionName = release.version_name ?: release.version_code.toString(),
            releasedAt = humanTime(release.released_at),
            release = release,
        )
    }

    private fun humanTime(iso: String?): String =
        runCatching {
            OffsetDateTime.parse(iso)
                .atZoneSameInstant(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
        }.getOrDefault("")

    /**
     * Скачивает apk в кеш и проверяет, что это ровно то, что выложили.
     * progress — проценты, 0..100. Бросает исключение с понятной
     * человеку причиной, если что-то не так.
     */
    suspend fun download(
        context: Context,
        update: Update,
        progress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val release = update.release
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        // Прошлые скачивания не копим: apk весит под сотню мегабайт.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "${BuildConfig.APPLICATION_ID}-${release.version_code}.apk")

        val request = Request.Builder().url(release.apk_url.orEmpty()).get().build()
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("сервер ответил ${response.code}")
            val body = response.body
            val total = release.apk_size.takeIf { it > 0 } ?: body.contentLength()
            var done = 0L
            var shown = -1
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != shown) {
                                shown = percent
                                progress(percent)
                            }
                        }
                    }
                }
            }
        }

        // Сверяем с тем, что назвал бэкенд: оборванная загрузка или
        // подменённый по дороге файл не должны дойти до установки.
        if (release.apk_size > 0 && file.length() != release.apk_size) {
            file.delete()
            error("файл скачался не целиком")
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (!release.apk_sha256.isNullOrBlank() && !sha.equals(release.apk_sha256, ignoreCase = true)) {
            file.delete()
            error("файл не совпал с выложенным")
        }
        file
    }

    /** Отдаёт apk системе на установку. */
    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.cache", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
