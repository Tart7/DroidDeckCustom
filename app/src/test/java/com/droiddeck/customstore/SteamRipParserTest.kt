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
        // Captured verbatim from steamrip.com/dice-hoarder-free-download/
        val page = """
            <div class="entry-content">
              <h2>Dice Hoarder Direct Download</h2>
              <a class="btn" href="//itsfileditchfiles.st/balpha48/2af58fa8b1596bbcdb51/Dice.Hoarder-SteamRIP.com.rar">Download</a>
            </div>
        """.trimIndent()

        val links = SteamRipParser.parseArchiveLinks(page)
        assertEquals(1, links.size)
        assertEquals(
            "https://itsfileditchfiles.st/balpha48/2af58fa8b1596bbcdb51/Dice.Hoarder-SteamRIP.com.rar",
            links.first(),
        )
    }

    @Test
    fun `protocol-relative archive link resolves to https`() {
        val html = """<a href="//itsfileditchfiles.st/x/Dice.Hoarder-SteamRIP.com.rar">dl</a>"""
        val links = SteamRipParser.parseArchiveLinks(html)

        assertEquals(1, links.size)
        assertEquals(
            "https://itsfileditchfiles.st/x/Dice.Hoarder-SteamRIP.com.rar",
            links.first(),
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
}
