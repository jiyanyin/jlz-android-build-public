package dev.jlz.presence.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeSettingsIdentityTest {
    @Test
    fun phoneFormFactorAlwaysUsesCanonicalPhoneId() {
        assertEquals("android-phone-native-n0", canonicalNativeDeviceId(599))
        assertEquals("android-phone-native-n0", canonicalNativeDeviceId(360))
    }

    @Test
    fun tabletFormFactorAlwaysUsesCanonicalTabletId() {
        assertEquals("android-tablet-native-n0", canonicalNativeDeviceId(600))
        assertEquals("android-tablet-native-n0", canonicalNativeDeviceId(800))
    }
}
