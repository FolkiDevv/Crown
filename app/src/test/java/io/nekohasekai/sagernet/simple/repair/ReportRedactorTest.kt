package io.nekohasekai.sagernet.simple.repair

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cleaning of the «Починка» report (connection-repair "Отчёт без секретов") on a synthetic report: a password, a
 * UUID, keys, the servers' addresses (a domain, a public and a private IP), a URL with a server's domain as its host,
 * a subscription link (also JSON-escaped) — none of them may be left, and the labels stay consistent.
 */
class ReportRedactorTest {

    private val password = "hunter2-Sup3rSecret"
    private val uuid = "b831381d-6324-4d53-ad4f-8cda48b30811"
    private val publicKey = "Z84J2IelR9ch3k8VtlVhhs5ycBUlXA7wHBWcBrjqnAw"
    private val privateKey = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="
    private val serverDomain = "vpn.example-provider.net"
    private val sni = "front.example-provider.net"
    private val serverIp = "203.0.113.45"
    private val privateServerIp = "192.168.77.10"
    private val subscriptionHost = "sub.provider-panel.io"
    private val subscriptionToken = "AbCdEf0123456789xyzTOKENqwerty"
    private val subscription = "https://$subscriptionHost/api/sub/$subscriptionToken"
    private val shortId = "6ba85179e30d4fc2"
    private val seed = "kcp-seed-Batch9"

    private val profile = """
        {"type":"vless","name":"NL 1","server":"$serverDomain","server_port":443,"uuid":"$uuid",
         "tls":{"enabled":true,"server_name":"$sni","reality":{"enabled":true,"public_key":"$publicKey","short_id":"0123abcd"}},
         "transport":{"type":"ws","host":"$sni","path":"/ws?ed=2048"}}
    """.trimIndent()

    private fun redactor(): ReportRedactor {
        val servers = ReportRedactor.serverNames(serverDomain, profile) +
            ReportRedactor.serverNames(serverIp, """{"type":"shadowsocks","server":"$serverIp","password":"$password"}""") +
            ReportRedactor.serverNames(privateServerIp, """{"type":"wireguard","peers":[{"address":"$privateServerIp:51820"}]}""")
        return ReportRedactor(servers, listOf(subscription))
    }

    /** A report as RepairReportExport puts it together: report lines, core log lines, a one-line config. */
    private val report = listOf(
        "note: IOException: connect to $serverDomain:443 failed",
        "[Info] outbound/vless[proxy]: handshake with $serverDomain:443 failed: EOF",
        "[Info] outbound/vless[proxy]: dial tcp cdn.$serverDomain:443: i/o timeout",
        "[Debug] dns: exchanged $serverDomain A 60",
        "[Info] connection to $serverIp:8388 refused",
        "[Info] wireguard: peer $privateServerIp:51820 handshake did not complete",
        "[Info] http: GET https://$serverDomain:8443/ws?ed=2048 -> 502",
        "[Info] url test: https://user:$password@$serverDomain/probe failed",
        "[Info] subscription: fetching $subscription",
        "[Info] subscription: host $subscriptionHost answered 200",
        "[Info] import vless://$uuid@$serverDomain:443?security=reality&pbk=$publicKey&sni=$sni#NL",
        "[Info] url test https://www.gstatic.com/generate_204 via proxy: 120 ms",
        "[Info] clash api listening at http://127.0.0.1:9090/ui",
        "[Warn] shadowsocks: password=$password rejected",
        "[Warn] wireguard private key $privateKey",
        """{"outbounds":[{"type":"vless","server":"$serverDomain","uuid":"$uuid","tls":{"server_name":"$sni","reality":{"public_key":"$publicKey"}}}],"route":{"rule_set":[{"url":"https:\/\/$subscriptionHost\/api\/sub\/$subscriptionToken"}]}}""",
        """{"remote":"https:\/\/$serverDomain\/dns-query"}""",
        // Xray's own spelling of the Reality settings and the mKCP seed.
        """[Info] Start Xray: {"realitySettings":{"serverName":"$sni","publicKey":"$publicKey","shortId":"$shortId","shortIds":["$shortId"]},"kcpSettings":{"seed":"$seed"}}""",
    )

    private val secrets = listOf(
        password, uuid, publicKey, privateKey, serverDomain, sni, serverIp, privateServerIp, subscriptionHost,
        subscriptionToken, subscription, "example-provider", "provider-panel", shortId, seed,
    )

    @Test
    fun noSecretIsLeft() {
        val out = redactor().redactText(report.joinToString("\n"))
        for (secret in secrets) {
            assertFalse("left: $secret in\n$out", out.contains(secret, ignoreCase = true))
        }
    }

    @Test
    fun serversAndLinksBecomeLabels() {
        val r = redactor()
        // A server's domain anywhere, as the host of a URL and its subdomains: one label.
        assertEquals("[Info] outbound/vless[proxy]: handshake with <server-1>:443 failed: EOF", r.redact(report[1]))
        assertEquals("[Info] outbound/vless[proxy]: dial tcp <server-1>:443: i/o timeout", r.redact(report[2]))
        assertEquals("[Info] http: GET https://<server-1>:8443/<redacted> -> 502", r.redact(report[6]))
        assertEquals("[Info] url test: https://<redacted>@<server-1>/<redacted> failed", r.redact(report[7]))
        // A private address the log export keeps is a server here.
        assertTrue(r.redact(report[5]).contains("peer <server-"))
        // The subscription link whole, its host alone.
        assertEquals("[Info] subscription: fetching <subscription-1>", r.redact(report[8]))
        assertEquals("[Info] subscription: host <subscription-1-host> answered 200", r.redact(report[9]))
        // A host no profile names: a label of its own; the same host, the same label.
        val gstatic = r.redact(report[11])
        assertTrue(gstatic, gstatic.contains("https://<host-1>/<redacted>"))
        assertEquals(gstatic, r.redact(report[11]))
        // Loopback stays readable.
        assertTrue(r.redact(report[12]).contains("http://127.0.0.1:9090/<redacted>"))
    }

    @Test
    fun xrayCamelCaseSecrets() {
        val json = JSONObject(redactor().redact(report[17]).substringAfter("Start Xray: "))
        val reality = json.getJSONObject("realitySettings")
        assertEquals("<redacted>", reality.getString("shortId"))
        assertEquals("<redacted>", reality.getJSONArray("shortIds").getString(0))
        assertEquals("<redacted>", reality.getString("serverName"))
        assertEquals("<redacted>", json.getJSONObject("kcpSettings").getString("seed"))
    }

    /** The final configuration: one JSON document that still parses, with nothing of the servers in it. */
    @Test
    fun configStaysValidJson() {
        val config = """
            {"sing_box":{"log":{"level":"info"},
              "inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"auto_route":true,"mtu":9000}],
              "outbounds":[{"type":"vless","tag":"proxy","server":"$serverDomain","server_port":443,"uuid":"$uuid",
                "tls":{"enabled":true,"server_name":"$sni","reality":{"enabled":true,"public_key":"$publicKey","short_id":"$shortId"}},
                "transport":{"type":"ws","path":"/ws","headers":{"Host":"$sni"}}},
               {"type":"shadowsocks","tag":"ss","server":"$privateServerIp","server_port":8388,"password":"$password"},
               {"type":"hysteria2","tag":"hy","server":"$serverIp","up_mbps":100,"auth_timeout":30,"obfs":{"type":"salamander","password":"$password"}}],
              "route":{"rule_set":[{"tag":"sub","type":"remote","url":"$subscription"}],"rules":[{"wifi_ssid":["Home WiFi"],"outbound":"direct"}]},
              "dns":{"servers":[{"tag":"dns-remote","address":"https://$serverDomain/dns-query"}]}},
             "xray":[{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"$serverDomain","port":443,"users":[{"id":"$uuid"}]}]},
               "streamSettings":{"realitySettings":{"serverName":"$sni","publicKey":"$publicKey","shortId":"$shortId","spiderX":"/"},"kcpSettings":{"seed":"$seed"}}}]}]}
        """.trimIndent().replace("\n", "")
        val out = redactor().redactJson(config)
        for (secret in secrets + "Home WiFi") assertFalse("left: $secret in\n$out", out.contains(secret, ignoreCase = true))
        val json = JSONObject(out)
        val tun = json.getJSONObject("sing_box").getJSONArray("inbounds").getJSONObject(0)
        assertEquals(9000, tun.getInt("mtu"))
        assertEquals("tun", tun.getString("type"))
        assertEquals(443, json.getJSONObject("sing_box").getJSONArray("outbounds").getJSONObject(0).getInt("server_port"))
        assertTrue(out.lines().size > 10)
    }

    @Test
    fun escapedJsonIsCleanedAndStillParses() {
        val line = redactor().redact(report[16])
        assertEquals("<server-1>", JSONObject(line).getString("remote").substringAfter("https://").substringBefore('/'))
    }

    @Test
    fun withoutServersTheLogRulesStillApply() {
        val r = ReportRedactor(emptyList(), emptyList())
        val out = r.redact("[Info] url test: https://user:$password@$serverDomain/probe uuid $uuid")
        assertFalse(out, out.contains(password))
        assertFalse(out, out.contains(uuid))
        assertFalse(out, out.contains(serverDomain))
    }

    @Test
    fun serverNamesOfAProfile() {
        val names = ReportRedactor.serverNames(serverDomain, profile)
        assertEquals(setOf(serverDomain, sni), names)
        assertEquals(
            setOf("2001:db8::7", "wg.example.org"),
            ReportRedactor.serverNames(
                "[2001:db8::7]:51820",
                """{"type":"wireguard","peers":[{"endpoint":"wg.example.org:51820"}],"address":"none"}""",
            ),
        )
    }

    @Test
    fun normalize() {
        assertEquals("vpn.example.com", ReportRedactor.normalize("VPN.Example.com.:443"))
        assertEquals("2001:db8::1", ReportRedactor.normalize("[2001:db8::1]:443"))
        assertEquals("10.0.0.1", ReportRedactor.normalize("10.0.0.1/32"))
        assertEquals("sub.example.com", ReportRedactor.normalize("https://u:p@sub.example.com:8443/x?y"))
        assertEquals(null, ReportRedactor.normalize("ws"))
        assertEquals(null, ReportRedactor.normalize("0.0.0.0"))
    }
}
