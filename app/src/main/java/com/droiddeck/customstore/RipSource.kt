package com.droiddeck.customstore

/** A download mirror listed on a SteamRip game page. */
data class RipSource(
    /** Display name as the page shows it, e.g. "GOFILE", "Fileditch". */
    val host: String,
    /** Absolute URL after resolving protocol-relative references. */
    val url: String,
    /**
     * True when the link points straight at an archive. False for host landing pages
     * (Gofile, BZZHR) that need a second step to reach the file.
     */
    val direct: Boolean,
    /**
     * True for "Update-Only" archives. These patch an existing install and must never be
     * picked as the primary download — that is how you end up installing a 40 MB diff
     * instead of the game.
     */
    val updateOnly: Boolean,
)
