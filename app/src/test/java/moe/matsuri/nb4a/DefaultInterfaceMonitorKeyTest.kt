package moe.matsuri.nb4a

import go.Seq
import io.throneproj.mobile.InterfaceUpdateListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultInterfaceMonitorKeyTest {

    // Same shape as gomobile's Mobile$proxyInterfaceUpdateListener: a non-public final class with a public final
    // refnum field and identity equality; a new one is created every time the Go listener crosses into Java.
    private class FakeProxy(@JvmField val refnum: Int) : Seq.Proxy, InterfaceUpdateListener {
        override fun incRefnum(): Int = error("must not touch the Go reference count")
        override fun updateDefaultInterface(name: String?, index: Int, expensive: Boolean, constrained: Boolean) = Unit
        override fun updateNetworkPath(path: String?) = Unit
    }

    private class PlainListener : InterfaceUpdateListener {
        override fun updateDefaultInterface(name: String?, index: Int, expensive: Boolean, constrained: Boolean) = Unit
        override fun updateNetworkPath(path: String?) = Unit
    }

    @Test
    fun proxiesOfTheSameGoListenerShareOneKey() {
        val onStart = FakeProxy(-42)
        val onClose = FakeProxy(-42)
        assertNotEquals(onStart, onClose)
        assertEquals(defaultInterfaceMonitorKey(onStart), defaultInterfaceMonitorKey(onClose))
        assertEquals(defaultInterfaceMonitorKey(onStart).hashCode(), defaultInterfaceMonitorKey(onClose).hashCode())
    }

    @Test
    fun differentGoListenersKeepDifferentKeys() {
        assertNotEquals(defaultInterfaceMonitorKey(FakeProxy(-42)), defaultInterfaceMonitorKey(FakeProxy(-43)))
    }

    @Test
    fun closeRemovesTheEntryAddedByStart() {
        // DefaultNetworkListener keeps its listeners in a map keyed this way
        val listeners = mutableMapOf<Any, InterfaceUpdateListener>()
        val box1 = FakeProxy(-42)
        val box2 = FakeProxy(-43)
        listeners[defaultInterfaceMonitorKey(box1)] = box1
        listeners[defaultInterfaceMonitorKey(box2)] = box2
        listeners.remove(defaultInterfaceMonitorKey(FakeProxy(-42)))
        listeners.remove(defaultInterfaceMonitorKey(FakeProxy(-43)))
        assertTrue(listeners.isEmpty())
    }

    @Test
    fun listenerThatIsNotAGoProxyIsItsOwnKey() {
        val listener = PlainListener()
        assertSame(listener, defaultInterfaceMonitorKey(listener))
    }
}
