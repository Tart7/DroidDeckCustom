package com.droiddeck.customstore

import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store

/**
 * Tolerant HTML scraping helpers.
 *
 * Upstream ships no HTML parser and adding one would widen the merge surface, so pages are
 * matched with regex. Real-world HTML is messy, so everything here is written to survive:
 *  - attribute order varies  -> look for any attribute, not a fixed sequence
 *  - quotes vary             -> accept ' and "
 *  - tags nest arbitrarily   -> scan outward from anchors instead of trusting wrappers
 *  - entities                -> decoded before display
 */
internal object HtmlText {

    val ATTR_HREF = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    val ATTR_SRC = Regex("""\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    val ATTR_ALT = Regex("""\balt\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    val ATTR_SRCSET = Regex("""\bsrcset\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    /** Any <img ...> tag, whatever its attribute order. */
    val IMG_TAG = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)

    /** Strip tags but keep the text, with entities decoded. */
    fun text(raw: String): String = unescape(raw.replace(Regex("""<[^>]+>"""), " "))
        .replace(Regex("""\s+"""), " ")
        .trim()

    fun unescape(s: String): String {
        if (!s.contains('&')) return s
        var out = s
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&hellip;", "…")
            .replace("&ndash;", "–")
            .replace("&mdash;", "—")
        // Numeric entities: &#8217; and friends.
        out = NUMERIC.replace(out) { m ->
            val code = m.groupValues[1].toIntOrNull()
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        return out
    }

    private val NUMERIC = Regex("""&#(\d{1,7});""")

    /** First usable image URL in a fragment; prefers a real file over a data: placeholder. */
    fun image(fragment: String): String? {
        for (tag in IMG_TAG.findAll(fragment).take(4)) {
            val src = ATTR_SRC.find(tag.value)?.groupValues?.get(1)
            if (src != null && isImageUrl(src)) return absolutize(src)
            // Lazy-loading themes park the real URL in srcset or data-src.
            val set = ATTR_SRCSET.find(tag.value)?.groupValues?.get(1)
            val fromSet = set?.split(',')?.firstOrNull()?.trim()?.substringBefore(' ')
            if (fromSet != null && isImageUrl(fromSet)) return absolutize(fromSet)
        }
        return null
    }

    private fun isImageUrl(u: String): Boolean {
        val l = u.lowercase()
        if (l.startsWith("data:")) return false
        if (l.endsWith(".svg")) return false
        return l.contains(".jpg") || l.contains(".jpeg") || l.contains(".png") ||
            l.contains(".webp") || l.contains(".avif") || l.startsWith("http")
    }

    fun absolutize(url: String, base: String = SteamRipSite.BASE): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> base + url
        else -> "$base/$url"
    }

    /**
     * Pull the biggest heading out of a fragment. Falls back to a link's title attribute
     * and finally to a slug turned into words.
     */
    fun title(fragment: String): String? {
        HEADING.find(fragment)?.let { m ->
            text(m.groupValues[1]).takeIf { it.isNotBlank() && it.length <= 140 }?.let { return it }
        }
        ATTR_ALT.find(fragment)?.groupValues?.get(1)?.let { alt ->
            unescape(alt).trim().takeIf { it.isNotBlank() && it.length <= 140 }?.let { return it }
        }
        return null
    }

    private val HEADING = Regex(
        """<h[1-4][^>]*>(.*?)</h[1-4]>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    /** "hollow-knight-silksong" -> "Hollow Knight Silksong". */
    fun fromSlug(slug: String): String = slug
        .replace('-', ' ')
        .replace('_', ' ')
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { w ->
            if (w.length <= 2) w.uppercase()
            else w[0].uppercase() + w.substring(1)
        }

    /** True for paths that are site furniture rather than a game. */
    fun isBoringPath(path: String): Boolean {
        val p = path.trim('/').lowercase()
        if (p.isEmpty() || p.contains('/')) return true
        return BORING.any { p == it || p.startsWith("$it/") } || p.startsWith("page")
    }

    private val BORING = listOf(
        "about", "contact", "privacy", "privacy-policy", "terms", "dmca", "disclaimer",
        "faq", "support", "request", "donate", "news", "blog", "category", "tag",
        "author", "feed", "sitemap", "wp-admin", "wp-content", "wp-json", "login",
        "register", "account", "cart", "checkout", "search", "how-to-download",
        "install", "guide", "tutorial", "comments", "amp", "assets", "static",
    )

    fun buildItem(
        store: Store,
        slug: String,
        title: String,
        art: String?,
        href: String,
    ): CatalogItem = CatalogItem(
        store = store,
        id = slug,
        title = title,
        imageUrl = art,
        tallImageUrl = art,
        isFree = true,
        // owned=true is what routes the detail page's primary button to
        // StoresState.requestInstall (the built-in downloader) instead of opening a browser.
        owned = true,
        includedWith = store.label,
        storeUrl = href,
        extra = mapOf("slug" to slug),
    )
}
