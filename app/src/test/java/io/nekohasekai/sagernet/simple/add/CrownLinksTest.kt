package io.nekohasekai.sagernet.simple.add

import io.nekohasekai.sagernet.simple.add.ImportText.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CrownLinksTest {

    private fun sub(url: String, name: String) = "clash://install-config?url=$url&name=$name"

    @Test
    fun subscriptionByQuery() {
        // Remnawave: crown://addsub?url={{SUBSCRIPTION_LINK}}&name=…, the URL inserted as it is
        assertEquals(
            sub("https%3A%2F%2Fsub.example.com%2Fapi%2Fsub%2FAbC123", "My%20VPN"),
            CrownLinks.toThrone("crown://addsub?url=https://sub.example.com/api/sub/AbC123&name=My%20VPN"),
        )
        // encoded, other order, upper case, extra parameters, a slash before the query
        assertEquals(
            sub("https%3A%2F%2Fa.b%2Fc%3Fx%3D1%26y%3D2", "%D0%9A%D0%BE%D1%80%D0%BE%D0%BD%D0%B0"),
            CrownLinks.toThrone(" CROWN://AddSub/?name=%D0%9A%D0%BE%D1%80%D0%BE%D0%BD%D0%B0&x=0&URL=https%3A%2F%2Fa.b%2Fc%3Fx%3D1%26y%3D2 "),
        )
        assertEquals(sub("https%3A%2F%2Fa.b%2Fc", ""), CrownLinks.toThrone("crown://install-config?url=https://a.b/c"))
        // '+' is not a space: subscription tokens may hold it
        assertEquals(sub("https%3A%2F%2Fa.b%2Fc%2Bd", "a%2Bb"), CrownLinks.toThrone("crown://addsub?url=https://a.b/c+d&name=a+b"))
        // an emoji next to an escape, a malformed escape kept as it is
        assertEquals(
            sub("https%3A%2F%2Fa.b%2Fc", "%F0%9F%91%91%20VPN%20%25-1"),
            CrownLinks.toThrone("crown://addsub?url=https://a.b/c&name=👑%20VPN%20%-1"),
        )
        // a raw URL keeps its own query and escapes, with the name after or before it
        assertEquals(
            sub("https%3A%2F%2Fa.b%2Fs%3Ftoken%3Da%2526c%26b%3D1", "n"),
            CrownLinks.toThrone("crown://addsub?url=https://a.b/s?token=a%26c&b=1&name=n"),
        )
        assertEquals(
            sub("https%3A%2F%2Fa.b%2Fs%3Ftoken%3Da%2526c%26b%3D1", "My%20VPN"),
            CrownLinks.toThrone("crown://addsub?name=My%20VPN&URL=https://a.b/s?token=a%26c&b=1"),
        )
        // the same URL fully encoded: decoded once, so %2526 becomes %26
        assertEquals(
            sub("https%3A%2F%2Fa.b%2Fs%3Ftoken%3Da%2526c%26b%3D1", "n"),
            CrownLinks.toThrone("crown://addsub?url=https%3A%2F%2Fa.b%2Fs%3Ftoken%3Da%2526c%26b%3D1&name=n"),
        )
        // no URL: still a subscription link, so the classic interface says that the URL is missing
        assertEquals(sub("", "x"), CrownLinks.toThrone("crown://addsub?name=x"))
        assertEquals(sub("", ""), CrownLinks.toThrone("crown://addsub"))
    }

    @Test
    fun subscriptionByPath() {
        assertEquals(
            sub("https%3A%2F%2Fsub.example.com%2Fs%3Ftoken%3Da%26b%3D1", ""),
            CrownLinks.toThrone("crown://addsub/https://sub.example.com/s?token=a&b=1"),
        )
        assertEquals(sub("http%3A%2F%2F1.2.3.4%3A8080%2Fs", "My%20VPN"), CrownLinks.toThrone("crown://addsub/http://1.2.3.4:8080/s#My%20VPN"))
    }

    @Test
    fun throneAliases() {
        assertEquals("throne://addsub/aHR0cHM6Ly9hLmIvYyNu", CrownLinks.toThrone("crown://addsub/aHR0cHM6Ly9hLmIvYyNu"))
        assertEquals("throne://add/eyJ0eXBlIjoic29ja3MifQ", CrownLinks.toThrone("crown://add/eyJ0eXBlIjoic29ja3MifQ"))
        assertEquals("throne://route/abc", CrownLinks.toThrone("crown://route/abc"))
        assertEquals("throne://remoteroute/abc", CrownLinks.toThrone("crown://remoteroute/abc"))
    }

    @Test
    fun otherTextUnchanged() {
        for (text in listOf("throne://addsub/abc", "vless://id@h:1#crown://x", " https://a.b/c ", "", "crown:/x")) {
            assertSame(text, CrownLinks.toThrone(text))
        }
    }

    @Test
    fun rewrittenLinksAreRecognised() {
        assertEquals(Kind.SUBSCRIBE_LINK, ImportText.kind(CrownLinks.toThrone("crown://addsub?url=https://a.b/c&name=n")))
        assertEquals(Kind.SUBSCRIBE_LINK, ImportText.kind(CrownLinks.toThrone("crown://addsub/aHR0cHM6Ly9hLmIvYyNu")))
        assertEquals(Kind.ROUTE_LINK, ImportText.kind(CrownLinks.toThrone("crown://route/abc")))
    }
}
