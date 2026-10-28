package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccountNumberTest {

    @Test
    fun `accepts a number with a correct check digit`() {
        assertTrue(MobileBackend.isValidAccountNumber("4821093755126608"))
    }

    @Test
    fun `accepts the number written in groups`() {
        assertTrue(MobileBackend.isValidAccountNumber("4821 0937 5512 6608"))
    }

    @Test
    fun `rejects a typo in one digit`() {
        assertFalse(MobileBackend.isValidAccountNumber("4821093755126609"))
        assertFalse(MobileBackend.isValidAccountNumber("4821093755126618"))
    }

    @Test
    fun `rejects a wrong length`() {
        assertFalse(MobileBackend.isValidAccountNumber("482109375512660"))
        assertFalse(MobileBackend.isValidAccountNumber("48210937551266080"))
        assertFalse(MobileBackend.isValidAccountNumber(""))
    }

    @Test
    fun `rejects letters and punctuation`() {
        assertFalse(MobileBackend.isValidAccountNumber("4821-0937-5512-6608"))
        assertFalse(MobileBackend.isValidAccountNumber("4821093755126608a"))
    }
}
