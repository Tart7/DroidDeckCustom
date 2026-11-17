package com.droiddeck.customstore

import android.content.Context
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store

/**
 * steamrip.com — WordPress (TieLabs theme) behind Cloudflare.
 *
 * This class only handles transport and DroidDeck wiring; all markup parsing lives in
 * [SteamRipParser], which is pure and unit-tested against captured HTML.
 */
object SteamRipSite : CustomStoreSite {

    const val BASE = SteamRipParser.BASE

    override val store: Store = Store.STEAMRIP

    override fun catalog(context: Context): List<CatalogItem> = parse(context, "$BASE/") { }

    override fun search(context: Context, query: String): List<CatalogItem> {
        if (query.isBlank()) return emptyList()
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        return parse(context, "$BASE/?s=$q") { }
    }

    /** Exposed so diagnostics can explain an empty result instead of guessing. */
    fun parseWithLog(context: Context, url: String, log: (String) -> Unit): List<CatalogItem> =
        parse(context, url, log)

    private fun parse(context: Context, url: String, log: (String) -> Unit): List<CatalogItem> {
        val html = CustomFetch.html(context, url, log)
        if (html.isNullOrBlank()) {
            log("steamrip: empty response")
            return emptyList()
        }
        log("steamrip: ${html.length} chars from $url")
        if (html.contains("Just a moment") && !html.contains("post-item")) {
            log("steamrip: still behind the Cloudflare challenge")
            return emptyList()
        }

        val entries = SteamRipParser.parseListing(html)
        log("steamrip: parsed ${entries.size} games")
        return entries.map {
            HtmlText.buildItem(store, it.slug, it.title, it.art, it.url)
        }
    }

    override fun resolveDownload(
        context: Context, item: CatalogItem, log: (String) -> Unit,
    ): String? {
        val html = CustomFetch.html(context, item.storeUrl, log) ?: return null
        val links = SteamRipParser.parseArchiveLinks(html)
        log("steamrip: ${links.size} archive link(s) on ${item.title}")
        return links.firstOrNull()
    }
}
