package com.rpgmaps.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What people type with a TV remote is rarely exactly `host:port`. */
class NormalizeTest {

    private fun n(raw: String) = PlayerActivity.normalize(raw)

    @Test fun plainAddressGetsTheDefaultPort() = assertEquals("192.168.1.34:8770", n("192.168.1.34"))
    @Test fun explicitPortIsKept() = assertEquals("192.168.1.34:8771", n("192.168.1.34:8771"))
    @Test fun schemeAndSlashAreDropped() = assertEquals("192.168.1.34:8770", n("http://192.168.1.34:8770/"))
    @Test fun pathIsDropped() = assertEquals("10.0.0.5:8770", n(" 10.0.0.5/health "))
    @Test fun ipv6WithoutPort() = assertEquals("[fe80::1]:8770", n("[fe80::1]"))
    @Test fun ipv6WithPort() = assertEquals("[fe80::1]:9000", n("[fe80::1]:9000"))
    @Test fun blankIsRejected() = assertNull(n("   "))
    @Test fun spacesAreRejected() = assertNull(n("192.168 .1.34"))
}
