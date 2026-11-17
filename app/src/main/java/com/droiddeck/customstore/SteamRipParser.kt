package com.droiddeck.customstore

/** One parsed listing entry, before it becomes a CatalogItem. */
data class RipEntry(val slug: String, val title: String, val art: String?, val url: String)

/**
 * Pure parsing for steamrip listing and game pages — no Android, no network.
 *
 * Kept separate from [SteamRipSite] so it can be tested against captured HTML with a plain
 * JVM test instead of an emulator.
 */
object SteamRipParser {

    const val BASE = "https://steamrip.com"

    /**
     * Cards are `<li class="post-item ...">`. Parsing is anchored on this class rather than
     * on `<article>` (the site renders none) or tag nesting (the theme nests heavily).
     */
    private val CARD = Regex(
        """<li[^>]*class="[^"]*\bpost-item\b[^"]*"[^>]*>.*?</li>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private val POST_TITLE = Regex(
        """<h2[^>]*class="[^"]*the-post-title[^"]*"[^>]*>(.*?)</h2>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private val LABEL = Regex("""aria-label=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    /**
     * The heading's inner text starts with release year and size, e.g. "2026 | 2.2 GB ".
     * That prefix is metadata, not part of the game name.
     */
    private val META_PREFIX = Regex(
        """^\s*\d{4}\s*[|·/]\s*\d+(?:\.\d+)?\s*(?:GB|MB)\s*""",
        RegexOption.IGNORE_CASE,
    )

    /** Archive links live on the game page and are protocol-relative. */
    private val ARCHIVE_HREF = Regex(
        """href=["']([^"']+\.(?:rar|zip|7z)(?:\?[^"']*)?)["']""",
        RegexOption.IGNORE_CASE,
    )

    fun parseListing(html: String): List<RipEntry> {
        val out = LinkedHashMap<String, RipEntry>()

        for (card in CARD.findAll(html)) {
            val block = card.value
            val href = HtmlText.ATTR_HREF.find(block)?.groupValues?.get(1) ?: continue
            val abs = resolve(href)
            if (!abs.startsWith(BASE)) continue

            val slug = abs.removePrefix(BASE).substringBefore('#').substringBefore('?').trim('/')
            if (slug.isEmpty() || slug.contains('/')) continue
            if (out.containsKey(slug)) continue

            out[slug] = RipEntry(slug, cleanTitle(block, slug), HtmlText.image(block), abs)
        }
        return out.values.toList()
    }

    private fun cleanTitle(block: String, slug: String): String {
        // aria-label carries the clean name ("Dice Hoarder Free Download").
        LABEL.find(block)?.groupValues?.get(1)?.let { raw ->
            HtmlText.unescape(raw).trim().takeIf { it.isNotEmpty() }?.let { return it }
        }
        // Otherwise strip the "2026 | 2.2 GB " prefix off the heading.
        POST_TITLE.find(block)?.groupValues?.get(1)?.let { inner ->
            HtmlText.text(inner).replace(META_PREFIX, "").trim()
                .takeIf { it.isNotEmpty() }?.let { return it }
        }
        HtmlText.title(block)?.let { return it }
        return HtmlText.fromSlug(slug)
    }

    fun parseArchiveLinks(html: String): List<String> =
        ARCHIVE_HREF.findAll(html)
            .map { HtmlText.unescape(it.groupValues[1]) }
            .map(::resolve)
            .distinct()
            .toList()

    /** Handles the three href shapes the site uses: absolute, //cdn, and bare relative. */
    fun resolve(url: String): String {
        val u = url.trim()
        return when {
            u.startsWith("http://") || u.startsWith("https://") -> u
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> BASE + u
            else -> "$BASE/$u"
        }
    }
}
