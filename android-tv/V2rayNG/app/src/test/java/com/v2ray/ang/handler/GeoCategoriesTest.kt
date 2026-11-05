package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

class GeoCategoriesTest {

    private fun ByteArrayOutputStream.varint(value: Int) {
        var v = value
        while (v >= 0x80) {
            write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        write(v)
    }

    /** Запись справочника: поле 1 — название, дальше — содержимое категории. */
    private fun entry(name: String, payload: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply {
            write(0x0A)
            varint(name.length)
            write(name.toByteArray())
            write(payload)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(0x0A)
            varint(body.size)
            write(body)
        }.toByteArray()
    }

    @Test
    fun readsCategoryNamesAndSkipsContents() {
        // Содержимое длиннее 127 байт — длина записи займёт два байта.
        val big = ByteArray(300) { 0x12 }
        val data = entry("CATEGORY-ADS-ALL", big) + entry("ru", byteArrayOf(0x12, 0x00)) + entry("PRIVATE", ByteArray(0))

        val names = GeoCategories.readNames(ByteArrayInputStream(data))

        assertEquals(setOf("CATEGORY-ADS-ALL", "RU", "PRIVATE"), names)
    }

    @Test
    fun emptyFileHasNoCategories() {
        assertEquals(emptySet<String>(), GeoCategories.readNames(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun truncatedFileFails() {
        val data = entry("CATEGORY-ADS-ALL", ByteArray(50))
        assertThrows<EOFException> {
            GeoCategories.readNames(ByteArrayInputStream(data.copyOf(data.size - 10)))
        }
    }
}
