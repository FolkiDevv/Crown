package io.nekohasekai.sagernet.simple.regime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DnsMessageTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** An answer to [query] with [records] as (type, rdata), names compressed to the question. */
    private fun answer(query: ByteArray, records: List<Pair<Int, ByteArray>>, flags: Int = 0x8180): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(query, 0, 2)
        out.write(flags shr 8)
        out.write(flags and 0xff)
        out.write(bytes(0, 1, 0, records.size, 0, 0, 0, 0))
        out.write(query, 12, query.size - 12)
        for ((type, data) in records) {
            out.write(bytes(0xc0, 12, 0, type, 0, 1, 0, 0, 0x0e, 0x10, 0, data.size))
            out.write(data)
        }
        return out.toByteArray()
    }

    @Test
    fun queryHasTheQuestion() {
        val query = DnsMessage.query(0x1234, "ya.ru", DnsMessage.TYPE_A)
        assertArrayEquals(
            bytes(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 2, 'y'.code, 'a'.code, 2, 'r'.code, 'u'.code, 0, 0, 1, 0, 1),
            query,
        )
    }

    @Test
    fun answerAddressesAreRead() {
        val query = DnsMessage.query(7, "www.google.com", DnsMessage.TYPE_A)
        val v4 = bytes(142, 250, 74, 4)
        val cname = bytes(3, 'w'.code, 'w'.code, 'w'.code, 0xc0, 12)
        val message = answer(query, listOf(5 to cname, 1 to v4))
        val found = DnsMessage.addresses(message, id = 7)
        assertEquals(1, found.size)
        assertArrayEquals(v4, found[0])
    }

    @Test
    fun ipv6AnswersAreRead() {
        val query = DnsMessage.query(9, "example.org", DnsMessage.TYPE_AAAA)
        val v6 = ByteArray(16) { it.toByte() }
        assertArrayEquals(v6, DnsMessage.addresses(answer(query, listOf(28 to v6)), id = 9).single())
    }

    @Test
    fun errorsOtherTransactionsAndDamageGiveNothing() {
        val query = DnsMessage.query(3, "ya.ru", DnsMessage.TYPE_A)
        val ok = answer(query, listOf(1 to bytes(1, 2, 3, 4)))
        assertTrue(DnsMessage.addresses(ok, id = 4).isEmpty())
        assertTrue(DnsMessage.addresses(answer(query, listOf(1 to bytes(1, 2, 3, 4)), flags = 0x8183), id = 3).isEmpty())
        assertTrue(DnsMessage.addresses(answer(query, listOf(1 to bytes(1, 2, 3, 4)), flags = 0x8380), id = 3).isEmpty())
        assertTrue(DnsMessage.addresses(query, id = 3).isEmpty())
        assertTrue(DnsMessage.addresses(ok, length = ok.size - 2, id = 3).isEmpty())
        assertTrue(DnsMessage.addresses(ByteArray(5), id = 0).isEmpty())
    }

    @Test
    fun plainServersOnly() {
        assertEquals("77.88.8.8" to 53, DnsMessage.plainServer("77.88.8.8"))
        assertEquals("77.88.8.1" to 5353, DnsMessage.plainServer("udp://77.88.8.1:5353"))
        assertEquals("2a02:6b8::feed:0ff" to 53, DnsMessage.plainServer("2a02:6b8::feed:0ff"))
        assertEquals("2a02:6b8::feed:0ff" to 53, DnsMessage.plainServer("[2a02:6b8::feed:0ff]"))
        assertEquals("2a02:6b8::feed:0ff" to 54, DnsMessage.plainServer("udp://[2a02:6b8::feed:0ff]:54"))
        assertNull(DnsMessage.plainServer("https://common.dot.dns.yandex.net/dns-query"))
        assertNull(DnsMessage.plainServer("tls://77.88.8.8"))
        assertNull(DnsMessage.plainServer("dns.yandex.ru"))
        assertNull(DnsMessage.plainServer("300.1.1.1"))
        assertNull(DnsMessage.plainServer("1.1.1.1:0"))
    }
}
