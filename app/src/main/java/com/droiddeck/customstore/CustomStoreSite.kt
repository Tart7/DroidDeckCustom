package com.droiddeck.customstore

import android.content.Context
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store

/** A download site hosted inside the store UI. */
interface CustomStoreSite {
    val store: Store

    fun catalog(context: Context): List<CatalogItem>
    fun featured(context: Context): List<CatalogItem> = catalog(context).take(12)
    fun search(context: Context, query: String): List<CatalogItem>

    /** Direct archive URL for a game page, or null when it cannot be resolved. */
    fun resolveDownload(context: Context, item: CatalogItem, log: (String) -> Unit): String?
}
