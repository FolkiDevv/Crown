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

    /** Free text at the end of a JSON string ("…auth: xyz") must not take the closing quote with it (review #10). */
    @Test
    fun freeTextInJsonStringsKeepsTheJsonValid() {
        val r = redactor()
        val report = JSONObject()
            .put("note", "IOException: invalid auth: $password")
            .put("error", "connect failed, address: $serverDomain")
            .put("values", JSONObject().put("latency_ms", 120))
        val out = r.redactJson(report.toString())
        val json = JSONObject(out)
        assertFalse(out, out.contains(password))
        assertFalse(out, out.contains(serverDomain))
        assertTrue(json.getString("note").startsWith("IOException: invalid auth: "))
        assertEquals(120, json.getJSONObject("values").getInt("latency_ms"))
        // The error object of config(): the message alone is cleaned, the JSON is built around it.
        val error = JSONObject().put("error", r.redact("IllegalStateException: bad password: $password")).toString(2)
        assertFalse(error, error.contains(password))
        JSONObject(error)
    }

    /** Xray's gRPC `serviceName` (camelCase) like the sing-box `service_name`, in the config and in a log line. */
    @Test
    fun grpcServiceName() {
        val r = redactor()
        val serviceName = "grpc-Svc-b9secret"
        val config = """{"sing_box":{"outbounds":[{"type":"vless","transport":{"type":"grpc","service_name":"$serviceName"}}]},""" +
            """"xray":[{"outbounds":[{"streamSettings":{"network":"grpc","grpcSettings":{"serviceName":"$serviceName","multiMode":true}}}]}]}"""
        val out = r.redactJson(config)
        assertFalse(out, out.contains(serviceName))
        val grpc = JSONObject(out).getJSONArray("xray").getJSONObject(0).getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings").getJSONObject("grpcSettings")
        assertEquals("<redacted>", grpc.getString("serviceName"))
        assertTrue(grpc.getBoolean("multiMode"))
        val line = r.redact("""[Info] Start Xray: {"grpcSettings":{"serviceName":"$serviceName"}}""")
        assertFalse(line, line.contains(serviceName))
    }

    /**
     * Xray's own debug lines name the gRPC service and the server in plain words (device re-check of review #10): the
     * profile's secret values go anywhere, and a server name after "tcp:" gets its label.
     */
    @Test
    fun xrayDebugLines() {
        val xrayService = "XrSvc-b9rv-3390"
        val xrayHost = "xr.b9vx-test.example"
        val vcn = "vcn.b9vx-test.example"
        val xrayProfile = """{"type":"vless","server":"$xrayHost","server_port":443,"uuid":"$uuid",
            "tls":{"enabled":true,"server_name":"$sni","verifyPeerCertByName":"$vcn"},
            "transport":{"type":"grpc","serviceName":"$xrayService"}}"""
        assertTrue(ReportRedactor.secretValues(xrayProfile).containsAll(listOf(uuid, xrayService)))
        assertFalse(ReportRedactor.secretValues(xrayProfile).contains(xrayHost))
        val r = ReportRedactor(
            ReportRedactor.serverNames(xrayHost, xrayProfile),
            emptyList(),
            ReportRedactor.secretValues(xrayProfile),
        )
        val grpc = r.redact("[Debug] transport/internet/grpc: using gRPC tun mode service name: `$xrayService` stream name: `Tun`")
        assertEquals("[Debug] transport/internet/grpc: using gRPC tun mode service name: `<redacted>` stream name: `Tun`", grpc)
        val dial = r.redact("[Debug] transport/internet: dialing to tcp:$xrayHost:443")
        assertEquals("[Debug] transport/internet: dialing to tcp:<server-1>:443", dial)
        val config = r.redactJson("""{"xray":[{"streamSettings":{"tlsSettings":{"verifyPeerCertByName":"$vcn"},"grpcSettings":{"serviceName":"$xrayService"}}}]}""")
        for (secret in listOf(xrayService, vcn, "b9vx-test")) assertFalse(config, config.contains(secret, ignoreCase = true))
        JSONObject(config)
        // Short config words are no secrets: "/ws" under "path" stays readable elsewhere.
        assertFalse(ReportRedactor.secretValues("""{"transport":{"path":"/ws"}}""").contains("/ws"))
    }

    /** A key that names a server (DNS hosts) is cleaned like a value; the document stays JSON. */
    @Test
    fun hostKeysAreCleaned() {
        val out = redactor().redactJson("""{"dns":{"hosts":{"$serverDomain":["$serverIp"],"$sni":"$privateServerIp"}}}""")
        for (secret in listOf(serverDomain, sni, serverIp, privateServerIp)) assertFalse(out, out.contains(secret))
        val hosts = JSONObject(out).getJSONObject("dns").getJSONObject("hosts")
        assertTrue(hosts.toString(), hosts.has("<server-1>") && hosts.has("<server-2>"))
    }

    /** A password with an "@": LogRedactor cuts the credentials at the first one; nothing of the rest may stay (review). */
    @Test
    fun passwordWithAtSign() {
        val url = "[Info] url test: https://user:pa@ssword@$serverDomain/probe failed"
        val expected = "[Info] url test: https://<redacted>@<server-1>/<redacted> failed"
        val withoutSecrets = redactor().redact(url)
        assertEquals(expected, withoutSecrets)
        val servers = ReportRedactor.serverNames(serverDomain, profile)
        val withSecret = ReportRedactor(servers, emptyList(), listOf("pa@ssword")).redact(url)
        assertEquals(expected, withSecret)
        // An unknown host behind such credentials gets a label too.
        val unknown = ReportRedactor(emptyList(), emptyList()).redact("https://u:p@ss@intranet.example.org:8443/x")
        assertEquals("https://<redacted>@<host-1>:8443/<redacted>", unknown)
    }

    /** Every URL host but loopback is labelled, also one that free text would not take for a host (review). */
    @Test
    fun singleLabelUrlHosts() {
        val r = ReportRedactor(emptyList(), emptyList())
        assertEquals("[Info] GET http://<host-1>:8080/<redacted> -> 200", r.redact("[Info] GET http://gateway:8080/status -> 200"))
        assertEquals("[Info] GET http://<host-2>/<redacted>", r.redact("[Info] GET http://router.lan.example/admin"))
        assertEquals("[Info] GET http://<host-1>/<redacted>", r.redact("[Info] GET http://GATEWAY/"))
        // Loopback stays readable (LogRedactor's own "host:" rule takes the port after "localhost" — not this pass).
        val localhost = r.redact("[Info] GET http://localhost:9090/ui")
        assertTrue(localhost, localhost.startsWith("[Info] GET http://localhost:"))
        assertFalse(localhost, localhost.contains("<host-"))
        assertEquals("[Info] GET http://[::1]:9090/<redacted>", r.redact("[Info] GET http://[::1]:9090/ui"))
    }

    /** A secret with an IP-like part: masked before LogRedactor turns that part into "<ip>" (review). */
    @Test
    fun secretWithAnIpInside() {
        val secret = "pw-203.0.113.9-Zq"
        val profile = """{"type":"shadowsocks","server":"$serverDomain","password":"$secret"}"""
        val r = ReportRedactor(ReportRedactor.serverNames(serverDomain, profile), emptyList(), ReportRedactor.secretValues(profile))
        for (line in listOf(
            "[Warn] auth failed for $secret",
            "[Info] ss://aes:$secret@$serverDomain:8388 failed",
            """{"note":"bad $secret here"}""",
        )) {
            val out = r.redact(line)
            for (part in listOf(secret, "pw-", "-Zq", "203.0.113.9")) assertFalse(out, out.contains(part))
            assertTrue(out, out.contains("<redacted>"))
            assertFalse(out, out.contains("\uE000"))
        }
    }

    /** A server with a single-label name ("gateway"): labelled where it stands as a host, the word itself stays (review). */
    @Test
    fun singleLabelServer() {
        val profile = """{"type":"vless","server":"gateway","server_port":443,"uuid":"$uuid","transport":{"type":"ws","host":"none"}}"""
        val names = ReportRedactor.serverNames("gateway:443", profile)
        assertEquals(setOf("gateway"), names)
        val r = ReportRedactor(names + ReportRedactor.serverNames("vpn", "{}"), emptyList())
        assertEquals("[Debug] dialing to tcp:<server-1>:443", r.redact("[Debug] dialing to tcp:gateway:443"))
        assertEquals("[Info] connect to <server-1>:8443 failed", r.redact("[Info] connect to GATEWAY:8443 failed"))
        assertEquals("[Info] GET http://<server-1>/<redacted>", r.redact("[Info] GET http://gateway/status"))
        assertTrue(r.redact("[Info] import vless://$uuid@gateway:443?type=ws#gw").contains("vless://<redacted>@<server-1>:443"))
        assertTrue(r.redact("[Info] user@vpn connected").contains("user@<server-2>"))
        // As plain words they are no hosts: «VPN service», «the gateway is down» stay readable.
        assertEquals("[Info] the gateway is down, VPN service on", r.redact("[Info] the gateway is down, VPN service on"))
        // Words a profile writes for "no address" are no servers.
        assertEquals(emptySet<String>(), ReportRedactor.serverNames("none", """{"address":"auto","endpoint":"8080"}"""))
    }

    /**
     * A single-label server name that identifies something ("mybox7", "дача5") is replaced as a plain word too; a
     * generic one (GENERIC_NAMES) stays readable as a word and is labelled only as a host (review).
     */
    @Test
    fun distinctiveSingleLabelServer() {
        val names = ReportRedactor.serverNames("mybox7", "{}") + ReportRedactor.serverNames("дача5", "{}") +
            ReportRedactor.serverNames("proxy", "{}")
        assertEquals(setOf("mybox7", "дача5", "proxy"), names)
        val r = ReportRedactor(names, emptyList())
        assertEquals("[Info] <server-1> handshake failed", r.redact("[Info] mybox7 handshake failed"))
        assertEquals("[Info] outbound <server-1> down", r.redact("[Info] outbound MYBOX7 down"))
        assertEquals("[Info] сервер <server-2> не отвечает", r.redact("[Info] сервер дача5 не отвечает"))
        assertEquals("[Info] tcp:<server-1>:443 and https://<server-1>/<redacted>", r.redact("[Info] tcp:mybox7:443 and https://mybox7/x"))
        // Part of a longer word is not the name.
        assertEquals("[Info] mybox77 and xmybox7 and дача55", r.redact("[Info] mybox77 and xmybox7 and дача55"))
        // A generic name: a word stays, a host is labelled.
        assertEquals("[Info] proxy started, dial tcp:<server-3>:1080", r.redact("[Info] proxy started, dial tcp:proxy:1080"))
        // The report's own label words are never server names.
        assertEquals(emptySet<String>(), ReportRedactor.serverNames("redacted", """{"address":"server-1"}"""))
        assertEquals("[Info] password=<redacted> uuid <uuid>", r.redact("[Info] password=secret12 uuid $uuid"))
    }

    /** An IPv6 server in any spelling — compressed, full, zero-padded, with brackets and port (neighbouring case). */
    @Test
    fun ipv6ServerInAnySpelling() {
        val names = ReportRedactor.serverNames("[fd00:0:0:0::1]:443", """{"type":"vless"}""")
        val r = ReportRedactor(names, emptyList())
        for (spelling in listOf("fd00::1", "fd00:0:0:0:0:0:0:1", "fd00:0000:0000:0000:0000:0000:0000:0001")) {
            val out = r.redact("[Debug] dial tcp [$spelling]:443: connection refused")
            assertEquals("[Debug] dial tcp [<server-1>]:443: connection refused", out)
        }
        assertEquals("http://<server-1>:8080/<redacted>", r.redact("http://[fd00:0::1]:8080/x"))
        assertEquals(setOf("fd00::1", "fd00:0:0:0:0:0:0:1", "fd00:0000:0000:0000:0000:0000:0000:0001"), ReportRedactor.forms("fd00::1"))
    }

    /** An IDN server: the cores print punycode, the profile may hold Unicode, or the other way round (neighbouring case). */
    @Test
    fun idnServer() {
        val r = ReportRedactor(ReportRedactor.serverNames("сервер.пример.рф", "{}"), emptyList())
        val punycode = java.net.IDN.toASCII("сервер.пример.рф")
        assertEquals("[Debug] dialing to tcp:<server-1>:443", r.redact("[Debug] dialing to tcp:$punycode:443"))
        assertEquals("[Info] connect to <server-1>:443", r.redact("[Info] connect to СЕРВЕР.пример.рф:443"))
        assertEquals("https://<server-1>/<redacted>", r.redact("https://$punycode/x"))
        val fromPunycode = ReportRedactor(ReportRedactor.serverNames(punycode, "{}"), emptyList())
        assertEquals("[Info] connect to <server-1>:443", fromPunycode.redact("[Info] connect to сервер.пример.рф:443"))
    }

    /** Servers written with their port, and endpoint lists (neighbouring case). */
    @Test
    fun serversWithPorts() {
        val profile = """{"type":"wireguard","server":"vpn.example.org:8443","peers":[{"endpoint":"wg1.example.org:51820,wg2.example.org:51820"}]}"""
        val names = ReportRedactor.serverNames("vpn.example.org:8443", profile)
        assertEquals(setOf("vpn.example.org", "wg1.example.org", "wg2.example.org"), names)
        val out = ReportRedactor(names, emptyList()).redact("[Info] peer wg2.example.org:51820 and vpn.example.org:8443")
        assertEquals("[Info] peer <server-3>:51820 and <server-1>:8443", out)
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
