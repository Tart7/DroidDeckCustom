package com.droiddeck.customstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs against markup captured from the live site, so a theme change shows up here rather
 * than as an empty store at runtime.
 */
class SteamRipParserTest {

    private val sample: String by lazy {
        javaClass.classLoader!!.getResourceAsStream("steamrip-cards.html")!!
            .bufferedReader().readText()
    }

    @Test
    fun `parses the captured listing`() {
        val entries = SteamRipParser.parseListing(sample)

        assertTrue("expected cards to parse, got ${entries.size}", entries.isNotEmpty())
        println("parsed ${entries.size} entries")
        entries.forEach { println("  ${it.slug} | ${it.title} | ${it.art}") }
    }

    @Test
    fun `title has no year-size prefix`() {
        val entries = SteamRipParser.parseListing(sample)
        val dice = entries.firstOrNull { it.slug.startsWith("dice-hoarder") }
            ?: entries.first()

        assertTrue(
            "title still carries metadata: '${dice.title}'",
            !Regex("""^\d{4}\s*\|""").containsMatchIn(dice.title),
        )
        assertTrue("title is blank", dice.title.isNotBlank())
    }

    @Test
    fun `slug and url are consistent`() {
        SteamRipParser.parseListing(sample).forEach { e ->
            assertTrue("slug has slash: ${e.slug}", !e.slug.contains('/'))
            assertEquals(
                "url mismatch for ${e.slug}",
                "${SteamRipParser.BASE}/${e.slug}/",
                e.url,
            )
        }
    }

    @Test
    fun `relative image becomes absolute`() {
        SteamRipParser.parseListing(sample)
            .mapNotNull { it.art }
            .forEach { art ->
                assertTrue("image not absolute: $art", art.startsWith("http"))
            }
    }

    @Test
    fun `archive link from a real game page`() {
        // Mirror rows on the real page are <p> blocks: "<p>Fileditch<br>
        // <a href=\"//...\">DOWNLOAD HERE</a></p>".
        val page = """<p style="text-align: center;"><span><strong>Fileditch</strong></span><br> <a href="//itsfileditchfiles.st/balpha48/2af58fa8b1596bbcdb51/Dice.Hoarder-SteamRIP.com.rar" target="_blank" rel="nofollow">DOWNLOAD HERE</a> </p>"""

        val sources = SteamRipParser.parseSources(page)
        assertEquals(1, sources.size)
        assertEquals(
            "https://itsfileditchfiles.st/balpha48/2af58fa8b1596bbcdb51/Dice.Hoarder-SteamRIP.com.rar",
            sources.first().url,
        )
        assertTrue("should be a direct archive", sources.first().direct)
    }

    @Test
    fun `protocol-relative archive link resolves to https`() {
        val html = """<p>Fileditch <a href="//itsfileditchfiles.st/x/Dice.Hoarder-SteamRIP.com.rar">DOWNLOAD HERE</a></p>"""
        val sources = SteamRipParser.parseSources(html)

        assertEquals(1, sources.size)
        assertEquals(
            "https://itsfileditchfiles.st/x/Dice.Hoarder-SteamRIP.com.rar",
            sources.first().url,
        )
    }

    @Test
    fun `bare relative href resolves under the site`() {
        assertEquals(
            "https://steamrip.com/some-game-free-download/",
            SteamRipParser.resolve("some-game-free-download/"),
        )
    }

    @Test
    fun `cloudflare page yields nothing`() {
        assertTrue(SteamRipParser.parseListing("<html><title>Just a moment...</title></html>").isEmpty())
    }

    // ---------------------------------------------------------------- mirrors

    private val sourceSample: String by lazy {
        javaClass.classLoader!!.getResourceAsStream("steamrip-sources.html")!!
            .bufferedReader().readText()
    }

    @Test
    fun `finds every mirror on the forza page`() {
        val sources = SteamRipParser.parseSources(sourceSample)

        assertEquals("expected 4 mirrors", 4, sources.size)
        sources.forEach { println("  ${it.host} | direct=${it.direct} updateOnly=${it.updateOnly} | ${it.url}") }

        assertEquals(listOf("GOFILE", "BZZHR", "Fileditch", "Fileditch"), sources.map { it.host }.let {
            // The page writes the fourth one as "FileDitch"; host text is lowercased by text()
            it.map { h -> if (h.equals("FileDitch", true)) "Fileditch" else h }
        })
    }

    @Test
    fun `gofile and bzzhr are indirect`() {
        val sources = SteamRipParser.parseSources(sourceSample)
        val gofile = sources.first { it.url.contains("gofile") }
        val bzzhr = sources.first { it.url.contains("bzzhr") }

        assertTrue("gofile should need a second hop", !gofile.direct)
        assertTrue("bzzhr should need a second hop", !bzzhr.direct)
    }

    @Test
    fun `fileditch archives are direct`() {
        val direct = SteamRipParser.parseSources(sourceSample).filter { it.host.equals("Fileditch", true) }

        assertEquals(2, direct.size)
        assertTrue("fileditch should be direct", direct.all { it.direct })
    }

    @Test
    fun `update-only patch is flagged`() {
        val sources = SteamRipParser.parseSources(sourceSample)

        assertEquals("exactly one update patch", 1, sources.count { it.updateOnly })
        assertTrue(
            "the patch should be the Update-Only one",
            sources.first { it.updateOnly }.url.contains("Update-Only"),
        )
    }

    @Test
    fun `primary mirror is the full game not the patch`() {
        val primary = SteamRipParser.pickPrimary(SteamRipParser.parseSources(sourceSample))

        assertTrue("no primary picked", primary != null)
        assertTrue("picked the patch", !primary!!.updateOnly)
        assertTrue("primary should be direct", primary.direct)
        assertTrue(
            "primary is the wrong archive: ${primary.url}",
            primary.url.contains("Forza.Horizon.6-SteamRIP.com.rar"),
        )
    }

    @Test
    fun `protocol-relative mirrors become https`() {
        SteamRipParser.parseSources(sourceSample).forEach {
            assertTrue("not absolute: ${it.url}", it.url.startsWith("https://"))
        }
    }

    @Test
    fun `mirror classification matches host behaviour`() {
        val sources = SteamRipParser.parseSources(sourceSample)

        val fileditch = sources.first { it.host.equals("Fileditch", true) && !it.updateOnly }
        assertTrue("fileditch should be direct", fileditch.direct)

        val gofile = sources.first { it.url.contains("gofile") }
        assertTrue("gofile should not be direct", !gofile.direct)

        val bzzhr = sources.first { it.url.contains("bzzhr") }
        assertTrue("bzzhr should not be direct", !bzzhr.direct)
    }

    @Test
    fun `update-only patch is never picked as primary`() {
        // Even if the patch happens to come first in the page, it must not win.
        val patchFirst = """
            <p>FileDitch <a href="//itsfileditchfiles.st/x/Game-Update-Only-v1.rar">DOWNLOAD HERE</a></p>
            <p>Fileditch <a href="//itsfileditchfiles.st/x/Game-SteamRIP.com.rar">DOWNLOAD HERE</a></p>
        """.trimIndent()

        val sources = SteamRipParser.parseSources(patchFirst)
        assertEquals(2, sources.size)

        val primary = SteamRipParser.pickPrimary(sources)
        assertTrue("picked the patch", primary != null && !primary!!.updateOnly)
        assertTrue(
            "wrong archive: ${primary!!.url}",
            primary.url.contains("Game-SteamRIP.com.rar"),
        )
    }
}
