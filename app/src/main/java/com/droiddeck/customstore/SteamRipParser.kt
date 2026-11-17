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
     * Cards are `<li class="post-item ...">`. Anchored on this class rather than on
     * `<article>` (the site renders none) or tag nesting (the theme nests heavily).
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

    /**
     * Each mirror on a game page is its own paragraph:
     *
     *   <p>GOFILE <a href="//gofile.io/d/xxxx">DOWNLOAD HERE</a></p>
     *   <p>Fileditch <a href="//itsfileditchfiles.st/.../Game.rar">DOWNLOAD HERE</a></p>
     */
    private val HOST_PARA = Regex(
        """<(p|div)\b[^>]*>(?:(?!</\1>).)*?</\1>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private val ANY_HREF = Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    /** Hosts that only ever hand out a landing page; the file needs a second hop. */
    private val INDIRECT_HOSTS = listOf("gofile.io", "bzzhr.to", "bzzhr.com")

    private val UPDATE_ONLY = Regex("""update[-_ ]?only""", RegexOption.IGNORE_CASE)

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

    /**
     * All mirrors offered on a game page, in page order.
     *
     * A page typically lists Gofile, BZZHR and one or two FileDitch entries — the second
     * FileDitch one is usually an "Update-Only" patch, which callers must skip.
     */
    fun parseSources(html: String): List<RipSource> {
        val out = LinkedHashMap<String, RipSource>()

        for (para in HOST_PARA.findAll(html)) {
            val block = para.value
            val href = ANY_HREF.find(block)?.groupValues?.get(1) ?: continue
            // A mirror row is a host label plus a link to a known host or archive. The
            // button caption varies ("DOWNLOAD HERE", "Download", "HERE"), so it is not
            // used as the gate.
            if (!isMirrorHref(href) && !block.contains("DOWNLOAD", ignoreCase = true)) continue
            val url = resolve(href)
            if (!url.startsWith("http")) continue

            val hostText = HtmlText.text(block.replace(Regex("""DOWNLOAD HERE""", RegexOption.IGNORE_CASE), "")).trim()
            val host = hostText.ifBlank { hostOf(url) }
            if (out.containsKey(url)) continue

            out[url] = RipSource(
                host = host,
                url = url,
                direct = isDirect(url),
                updateOnly = UPDATE_ONLY.containsMatchIn(url) || UPDATE_ONLY.containsMatchIn(block),
            )
        }
        return out.values.toList()
    }

    /**
     * Best mirror to use without a second hop: a direct archive that is not an update patch.
     * Falls back to any non-update mirror so the caller can still try the indirect hosts.
     */
    fun pickPrimary(sources: List<RipSource>): RipSource? =
        sources.firstOrNull { it.direct && !it.updateOnly }
            ?: sources.firstOrNull { !it.updateOnly }

    fun isDirect(url: String): Boolean {
        val u = url.lowercase()
        if (INDIRECT_HOSTS.any { u.contains(it) }) return false
        return ARCHIVE_EXT.containsMatchIn(u) || u.contains("/download/")
    }

    private val ARCHIVE_EXT = Regex("""\.(rar|zip|7z)(\?|$)""", RegexOption.IGNORE_CASE)

    private fun isMirrorHref(href: String): Boolean {
        val u = href.lowercase()
        return ARCHIVE_EXT.containsMatchIn(u) || MIRROR_HOSTS.any { u.contains(it) }
    }

    private val MIRROR_HOSTS = listOf(
        "gofile.io", "bzzhr.to", "bzzhr.com", "fileditch", "itsfileditchfiles",
        "pixeldrain", "1fichier", "workupload", "mega.nz", "drive.google",
        "datanodes", "katfile", "rapidgator", "dropapk", "qiwi", "buzzheavier",
        "dlproxy", "/download/",
    )

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host.orEmpty().removePrefix("www.") }.getOrDefault("mirror")

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
