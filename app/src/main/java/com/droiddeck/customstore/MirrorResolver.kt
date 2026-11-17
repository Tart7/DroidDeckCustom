package com.droiddeck.customstore

import android.content.Context

/**
 * Turns a SteamRip mirror entry into a real file URL.
 *
 * What each host actually requires — verified against the live site:
 *
 *  Fileditch      direct .rar. Needs `Referer: https://steamrip.com/`, otherwise 302 home.
 *  Gofile         landing page; the file link comes from api.gofile.io and needs an
 *                 accountToken cookie plus a non-"server busy" file. Guest tokens get
 *                 `error-notPremium` on /contents, so this needs a real WebView session.
 *  BZZHR (bzzhr)  a Cloudflare Turnstile interstitial. Needs a real user gesture.
 *
 * Rather than pretend, [resolve] reports exactly which of those it hit so the UI can say
 * something useful instead of failing silently.
 */
object MirrorResolver {

    sealed interface Result {
        data class Direct(val url: String) : Result

        /** The mirror needs a browser session we cannot script (Turnstile, token, busy). */
        data class NeedsBrowser(val url: String, val reason: String) : Result

        data class Failed(val reason: String) : Result
    }

    fun resolve(context: Context, source: RipSource, log: (String) -> Unit): Result {
        val host = source.host.lowercase()

        return when {
            source.updateOnly -> Result.Failed("this mirror is an update-only patch")

            host.contains("fileditch") || source.url.contains("itsfileditchfiles") -> {
                // Direct, but the CDN rejects requests without a site Referer.
                Result.Direct(source.url)
            }

            host.contains("gofile") || source.url.contains("gofile.io") -> {
                probeGofile(context, source.url, log)
            }

            host.contains("bzzhr") || source.url.contains("bzzhr") -> {
                Result.NeedsBrowser(
                    source.url,
                    "bzzhr uses a Cloudflare Turnstile check that needs a real tap",
                )
            }

            source.direct -> Result.Direct(source.url)

            else -> Result.NeedsBrowser(source.url, "$host needs a browser session")
        }
    }

    /**
     * Gofile exposes its file list through api.gofile.io, which needs an accountToken
     * cookie and returns `error-notPremium` for guest tokens on private content. There is
     * no way to satisfy that from plain HTTP, so we open the page in the fetch WebView and
     * let its own JS produce a download control.
     */
    private fun probeGofile(context: Context, url: String, log: (String) -> Unit): Result {
        val html = CustomFetch.html(context, url, log) ?: return Result.Failed("gofile: no response")

        // The page tells us when the host itself is refusing the file.
        if (html.contains("server busy", ignoreCase = true)) {
            return Result.NeedsBrowser(url, "gofile: server busy for this file")
        }
        // The file name is rendered by JS; its presence means the listing loaded.
        val hasListing = html.contains(".rar", ignoreCase = true) ||
            html.contains(".zip", ignoreCase = true)
        if (!hasListing) return Result.NeedsBrowser(url, "gofile: listing did not render")

        return Result.NeedsBrowser(url, "gofile: needs an account session to mint the link")
    }
}
