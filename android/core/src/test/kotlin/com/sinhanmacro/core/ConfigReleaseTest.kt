package com.sinhanmacro.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigReleaseTest {
    @Test
    fun defaultsMergedWithUser() {
        val c = Config.parse("""{"symbols":{"TMV":{"name":"Direxion"}}}""")
        assertTrue(c.dryRun)
        assertTrue(c.confirmRequired) // 기본: 주문 전 승인 필요
        assertEquals(120, c.confirmTimeoutSec)
        assertEquals(5, c.maxOrdersPerDay)
        assertEquals(1, c.symbols.getValue("TMV").qty)
        assertEquals(OrderType.LIMIT, c.defaultOrderType)
        assertEquals(setOf(OrderType.LIMIT, OrderType.MARKET), c.allowedOrderTypes)
        assertEquals("지정가", c.orderTypeLabel(OrderType.LIMIT))
        assertEquals("Vwap 지정가", c.orderTypeLabel(OrderType.VWAP))
        assertEquals("America/New_York", c.timezone.id)
    }

    @Test
    fun invalidConfigsRejected() {
        listOf(
            "{not json",
            """{"symbols":{"tmv":{}}}""", // 소문자 티커
            """{"symbols":{"TOOLONGTICKER1":{}}}""",
            """{"symbols":{"TMV":{"qty":0}}}""",
            """{"order":{"default_type":"weird"}}""",
            """{"order":{"allowed_types":["limit"],"default_type":"market"}}""",
            """{"order":{"allowed_types":[]}}""",
            """{"confirm":{"timeout_sec":0}}""",
            """{"risk":{"timezone":"Mars/Base"}}""",
            """{"risk":{"trading_hours":["9am","3pm"]}}""",
            """{"flows":{"buy":{"steps":[{"tap":1}]}}}""",
            """{"flows":{"buy":{"steps":[{"do":"wait","when":"weird"}]}}}""",
        ).forEach { assertFailsWith<ConfigException>(it) { Config.parse(it) } }
    }

    @Test
    fun verifyWithTickerIsRequiredByDefault() {
        val noTicker = """{"flows":{"buy":{"steps":[{"do":"tap","sel":{"text":"x"},"final":true}],"verify":[{"sel":{"text":"y"}}]}}}"""
        assertFailsWith<ConfigException> { Config.parse(noTicker) }
        val noVerify = """{"flows":{"buy":{"steps":[{"do":"tap","sel":{"text":"x"},"final":true}]}}}"""
        assertFailsWith<ConfigException> { Config.parse(noVerify) }
        // 명시적으로 끄면 허용
        Config.parse(noVerify.dropLast(1) + ""","safety":{"require_verify":false}}""")
        // {ticker} 가 있으면 통과
        Config.parse("""{"flows":{"buy":{"steps":[{"do":"tap","sel":{"text":"x"},"final":true}],"verify":[{"sel":{"text":"{ticker}"}}]}}}""")
    }

    @Test
    fun exampleConfigFromAssetsParses() {
        val text = java.io.File("../app/src/main/assets/config.example.json").readText()
        val c = Config.parse(text)
        assertNotNull(c.flow("buy"))
        assertNotNull(c.flow("sell"))
        assertTrue(c.dryRun)
        assertTrue(c.confirmRequired)
        assertTrue(c.symbols.containsKey("TMV"))
        assertTrue(OrderType.LIMIT in c.allowedOrderTypes)
    }

    private fun release(tag: String = "v12", draft: Boolean = false, digest: String = "sha256:" + "a".repeat(64)) = """
        {"tag_name":"$tag","name":"빌드 12","draft":$draft,"prerelease":false,"body":"노트",
         "assets":[
           {"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":3},
           {"name":"sinhanmacro_v12_20261103.apk","browser_download_url":"https://x/a.apk","size":1234,"digest":"$digest"}
         ]}
    """.trimIndent()

    @Test
    fun releaseParsed() {
        val r = assertNotNull(ReleaseParser.parse(release()))
        assertEquals(12, r.versionCode)
        assertEquals("https://x/a.apk", r.apkUrl)
        assertEquals("a".repeat(64), r.sha256)
        assertTrue(ReleaseParser.isNewer(r, 11))
        assertFalse(ReleaseParser.isNewer(r, 12))
        assertFalse(ReleaseParser.isNewer(r, 13))
    }

    @Test
    fun releaseEdgeCases() {
        assertNull(ReleaseParser.parse(release(tag = "latest")))
        assertNull(ReleaseParser.parse(release(draft = true)))
        assertNull(ReleaseParser.parse("not json"))
        assertNull(ReleaseParser.parse("""{"tag_name":"v3","assets":[]}"""))
        assertNull(assertNotNull(ReleaseParser.parse(release(digest = "sha256:short"))).sha256) // 잘못된 digest 는 무시
    }
}
