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
        val c = Config.parse("""{"symbols":{"005930":{"name":"삼성전자"}}}""")
        assertTrue(c.dryRun)
        assertEquals(5, c.maxOrdersPerDay)
        assertEquals(1, c.symbols.getValue("005930").qty)
        assertEquals(listOf("매수", "BUY"), c.buyWords)
    }

    @Test
    fun invalidConfigsRejected() {
        listOf(
            "{not json",
            """{"symbols":{"123":{}}}""",
            """{"symbols":{"005930":{"qty":0}}}""",
            """{"order":{"price_mode":"weird"}}""",
            """{"risk":{"trading_hours":["9am","3pm"]}}""",
            """{"flows":{"buy":{"steps":[{"tap":1}]}}}""",
        ).forEach { assertFailsWith<ConfigException>(it) { Config.parse(it) } }
    }

    @Test
    fun exampleConfigFromAssetsParses() {
        val text = java.io.File("../app/src/main/assets/config.example.json").readText()
        val c = Config.parse(text)
        assertNotNull(c.flow("buy"))
        assertNotNull(c.flow("sell"))
        assertTrue(c.dryRun)
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
