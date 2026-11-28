package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TunSpecTest {

    private fun spec(allowed: List<Pair<String, Int>> = emptyList(), denied: List<Pair<String, Int>> = emptyList()) =
        TunSpec(
            mtu = 9000, autoRoute = true, dnsMode = 1, dns = listOf("172.19.0.2"),
            routes = listOf(listOf("172.19.0.1" to 30)), allowed = allowed, denied = denied,
            httpProxy = null, bypass = false, metered = false,
        )

    @Test
    fun allowedPackageInstalledAfterConnectBlocksReuse() {
        val uids = mutableMapOf("com.clock" to 10159)
        val packages = listOf("com.clock", "com.later")
        val atConnect = spec(allowed = TunSpec.apps(packages) { uids[it] })
        uids["com.later"] = 10238
        val atRestart = spec(allowed = TunSpec.apps(packages) { uids[it] })

        assertNotEquals(atConnect, atRestart)
        assertEquals("allowed", atRestart.diff(atConnect))
        assertEquals("allowed=2/1 denied=0/0", atConnect.appCounts())
        assertEquals("allowed=2/2 denied=0/0", atRestart.appCounts())
    }

    @Test
    fun deniedPackageInstalledOrRemovedBlocksReuse() {
        val installed = spec(denied = TunSpec.apps(listOf("com.auto")) { 10146 })
        val removed = spec(denied = TunSpec.apps(listOf("com.auto")) { null })

        assertNotEquals(installed, removed)
        assertEquals("denied", removed.diff(installed))
    }

    @Test
    fun reinstalledPackageWithAnotherUidBlocksReuse() {
        val before = spec(allowed = TunSpec.apps(listOf("com.app")) { 10200 })
        val after = spec(allowed = TunSpec.apps(listOf("com.app")) { 10201 })

        assertNotEquals(before, after)
    }

    @Test
    fun sameNamesWithOtherUidsNeedNewNetwork() {
        val uids = mutableMapOf("com.clock" to 10159)
        val packages = listOf("com.clock", "com.later")
        val atConnect = spec(allowed = TunSpec.apps(packages) { uids[it] })
        uids["com.later"] = 10248

        // Android would hand the network over by the unchanged names and keep the old uids.
        assertTrue(atConnect.needsNewNetwork(atConnect.now(metered = false) { uids[it] }, meteredApplies = true))
        val excluded = spec(denied = TunSpec.apps(listOf("com.auto")) { null })
        assertTrue(excluded.needsNewNetwork(excluded.now(metered = false) { 10146 }, meteredApplies = true))
    }

    @Test
    fun otherNamesOrNothingChangedKeepTheHandover() {
        val clock = spec(allowed = TunSpec.apps(listOf("com.clock")) { 10159 })
        val clockAndMaps = spec(allowed = TunSpec.apps(listOf("com.clock", "com.maps")) { 10159 })

        // Other names: Android makes a new network by itself.
        assertFalse(clock.needsNewNetwork(clockAndMaps, meteredApplies = true))
        assertFalse(clock.needsNewNetwork(clock.now(metered = false) { 10159 }, meteredApplies = true))
        assertFalse(clock.needsNewNetwork(clock.copy(mtu = 1500), meteredApplies = true))
    }

    @Test
    fun meteredChangeNeedsNewNetworkWhereTheBuilderHasIt() {
        val spec = spec()

        assertTrue(spec.needsNewNetwork(spec.copy(metered = true), meteredApplies = true))
        assertFalse(spec.needsNewNetwork(spec.copy(metered = true), meteredApplies = false))
    }

    @Test
    fun repeatedPackageIsLookedUpOnce() {
        var lookups = 0
        val apps = TunSpec.apps(listOf("com.app", "com.app", "com.gone", "com.gone")) {
            lookups++
            if (it == "com.app") 10200 else null
        }

        assertEquals(2, lookups)
        assertEquals(listOf("com.app" to 10200, "com.app" to 10200, "com.gone" to -1, "com.gone" to -1), apps)
    }

    @Test
    fun unchangedConfigurationAllowsReuse() {
        val apps = TunSpec.apps(listOf("com.clock")) { 10159 }

        assertEquals(spec(allowed = apps), spec(allowed = apps))
        assertEquals("none", spec(allowed = apps).diff(spec(allowed = apps)))
        assertEquals("no-interface", spec().diff(null))
    }
}
