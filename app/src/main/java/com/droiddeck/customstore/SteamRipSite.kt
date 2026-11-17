package com.droiddeck.customstore

import android.content.Context
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store

/**
 * steamrip.com — a WordPress site behind Cloudflare.
 *
 * Parsed with regex on purpose: upstream ships no HTML parser, and adding a dependency to
 * a fork is exactly the kind of change that turns a 6-line diff into a merge conflict.
 */
object SteamRipSite : CustomStoreSite {

    const val BASE = "https://steamrip.com"

    override val store: Store = Store.STEAMRIP

    override fun catalog(context: Context): List<CatalogItem> = parse(context, "$BASE/")

    override fun search(context: Context, query: String): List<CatalogItem> {
        if (query.isBlank()) return emptyList()
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        return parse(context, "$BASE/?s=$q")
    }

    override fun resolveDownload(
        context: Context, item: CatalogItem, log: (String) -> Unit,
    ): String? {
        val html = CustomFetch.html(context, item.storeUrl, log) ?: return null
        return archiveLinks(html).firstOrNull()
    }

    private fun parse(context: Context, url: String): List<CatalogItem> {
        val html = CustomFetch.html(context, url, { }) ?: return emptyList()
        if (html.contains("Just a moment")) return emptyList()

        val out = LinkedHashMap<String, CatalogItem>()

        // Each card is <article ...> ... <a href="https://steamrip.com/<slug>/"> ... <img ...> <h2>Title</h2>
        for (m in ARTICLE.findAll(html)) {
            val block = m.value
            val href = HREF.find(block)?.groupValues?.get(1) ?: continue
            if (!href.startsWith(BASE)) continue
            val path = href.removePrefix(BASE).trim('/')
            if (path.isEmpty() || path.contains('/') || path.startsWith("page") || path.startsWith("?")) continue

            if (out.containsKey(path)) continue

            val title = TITLE.find(block)?.groupValues?.get(1)?.let(::unescape)?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: IMG_ALT.find(block)?.groupValues?.get(1)?.let(::unescape)?.trim()
                    ?.takeIf { it.isNotEmpty() }
                ?: continue

            val art = IMG_SRC.find(block)?.groupValues?.get(1)

            out[path] = CatalogItem(
                store = store,
                id = path,
                title = title,
                imageUrl = art,
                tallImageUrl = art,
                isFree = true,
                owned = true,
                includedWith = store.label,
                storeUrl = href,
                extra = mapOf("slug" to path),
            )
        }
        return out.values.toList()
    }

    /** Direct archive links on a game page. */
    private fun archiveLinks(html: String): List<String> =
        HREF.findAll(html).map { it.groupValues[1] }
            .filter { u ->
                val l = u.lowercase()
                l.endsWith(".zip") || l.endsWith(".rar") || l.endsWith(".7z") ||
                    l.contains("/download/") || l.contains("dlproxy")
            }
            .distinct()
            .toList()

    private fun unescape(s: String): String = s
        .replace("&amp;", "&").replace("&#039;", "'").replace("&quot;", "\"")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")

    private val ARTICLE = Regex("""<article\b.*?</article>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val HREF = Regex("""href=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
    private val TITLE = Regex("""<h[12][^>]*>(.*?)</h[12]>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val IMG_SRC = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val IMG_ALT = Regex("""<img[^>]+alt=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
}
