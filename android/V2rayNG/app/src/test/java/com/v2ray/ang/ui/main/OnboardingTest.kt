package com.v2ray.ang.ui.main

import com.v2ray.ang.handler.MobileBackend.Status
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OnboardingTest {

    @Test
    fun welcomeShownOnlyWithoutAccountAndNotSkipped() {
        assertTrue(Onboarding.showWelcome(Status.NEEDS_ACCOUNT, skipped = false))
        assertFalse(Onboarding.showWelcome(Status.NEEDS_ACCOUNT, skipped = true))
        assertFalse(Onboarding.showWelcome(Status.ACTIVE, skipped = false))
        assertFalse(Onboarding.showWelcome(Status.NETWORK_ERROR, skipped = false))
        assertFalse(Onboarding.showWelcome(null, skipped = false))
    }

    @Test
    fun trialOfferShownOnlyWhenOfferedAndNotSkipped() {
        assertTrue(Onboarding.showTrialOffer(Status.OFFER, skipped = false))
        assertFalse(Onboarding.showTrialOffer(Status.OFFER, skipped = true))
        assertFalse(Onboarding.showTrialOffer(Status.NEEDS_ACCOUNT, skipped = false))
        assertFalse(Onboarding.showTrialOffer(null, skipped = false))
    }

    @Test
    fun accountItemsVisibleWithoutAccount() {
        assertTrue(Onboarding.showAccountItem(enabledByBackend = false, status = Status.NEEDS_ACCOUNT))
        assertTrue(Onboarding.showAccountItem(enabledByBackend = true, status = Status.ACTIVE))
        assertFalse(Onboarding.showAccountItem(enabledByBackend = false, status = Status.ACTIVE))
        assertFalse(Onboarding.showAccountItem(enabledByBackend = false, status = null))
    }
}
