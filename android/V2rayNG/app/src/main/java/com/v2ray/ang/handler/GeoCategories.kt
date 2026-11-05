package com.v2ray.ang.handler

import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream

/**
 * Какие категории есть в справочниках, которыми пользуется ядро.
 *
 * Ссылка в правиле на категорию, которой нет в geosite.dat или geoip.dat,
 * роняет ядро целиком: VPN перестаёт подниматься. Раньше разрешённые
 * категории были вписаны в приложение, и каждая новая требовала новой
 * сборки. Теперь сверяемся с самими файлами — с теми, что реально лежат
 * в папке ядра, — и правила с сервера с новыми категориями доходят без
 * обновления приложения.
 *
 * Формат файлов — protobuf, но разбирать его целиком незачем: подряд идут
 * записи поля 1, внутри каждой первым лежит поле 1 со строкой — названием
 * категории. Его и читаем, остальное пропускаем, не загружая в память.
 */
object GeoCategories {

    private class Cached(val modified: Long, val size: Long, val names: Set<String>)

    private val cache = mutableMapOf<String, Cached>()

    /**
     * Есть ли категория вида geosite:xxx или geoip:xxx в справочниках.
     * null — справочник прочитать не удалось, решать не нам.
     */
    fun contains(ref: String): Boolean? {
        val (kind, rawName) = ref.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val file = when (kind) {
            "geosite" -> AppConfig.GEOSITE_DAT
            "geoip" -> AppConfig.GEOIP_DAT
            else -> return null
        }
        // Уточнение через @ (geosite:google@ads) относится к доменам
        // внутри категории, саму категорию оно не меняет. В файле имена
        // записаны заглавными.
        val name = rawName.substringBefore("@").uppercase()
        if (name.isEmpty()) return false
        return names(file)?.contains(name)
    }

    @Synchronized
    private fun names(fileName: String): Set<String>? {
        val dir = Utils.userAssetPath(AngApplication.application)
        if (dir.isEmpty()) return null
        val file = File(dir, fileName)
        if (!file.isFile) return null

        cache[fileName]?.let {
            if (it.modified == file.lastModified() && it.size == file.length()) return it.names
        }
        return try {
            val names = BufferedInputStream(file.inputStream(), 64 * 1024).use(::readNames)
            cache[fileName] = Cached(file.lastModified(), file.length(), names)
            names
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Не прочитался справочник $fileName", e)
            null
        }
    }

    internal fun readNames(input: InputStream): Set<String> {
        val names = HashSet<String>()
        while (true) {
            val tag = readVarint(input, eofOk = true) ?: break
            require(tag == 0x0AL) { "неожиданное поле $tag" }
            val size = readVarint(input)!!
            var left = size

            val innerTag = readVarint(input)!!
            left -= varintSize(innerTag)
            if (innerTag == 0x0AL) {
                val length = readVarint(input)!!
                left -= varintSize(length)
                val bytes = ByteArray(length.toInt())
                readFully(input, bytes)
                left -= length
                names.add(String(bytes, Charsets.UTF_8).uppercase())
            }
            skipFully(input, left)
        }
        return names
    }

    private fun readVarint(input: InputStream, eofOk: Boolean = false): Long? {
        var value = 0L
        var shift = 0
        while (true) {
            val byte = input.read()
            if (byte < 0) {
                if (eofOk && shift == 0) return null
                throw EOFException()
            }
            value = value or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return value
            shift += 7
        }
    }

    private fun varintSize(value: Long): Int {
        var v = value
        var n = 1
        while (v >= 0x80) {
            v = v ushr 7
            n++
        }
        return n
    }

    private fun readFully(input: InputStream, bytes: ByteArray) {
        var off = 0
        while (off < bytes.size) {
            val n = input.read(bytes, off, bytes.size - off)
            if (n < 0) throw EOFException()
            off += n
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val n = input.skip(left)
            if (n <= 0) {
                if (input.read() < 0) throw EOFException()
                left--
            } else {
                left -= n
            }
        }
    }
}
