package com.droiddeck.customstore

import android.content.Context
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreBackend
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.download.DownloadEntry
import com.droiddeck.launcher.stores.download.DownloadQueue

/**
 * Backend for every store defined in this package.
 *
 * This is the fork's own code. It talks to upstream through the public StoreBackend
 * interface only — real types, no reflection — so upstream refactors show up as compile
 * errors here instead of silent runtime failures.
 */
object CustomStoreBackend : StoreBackend {

    override val store: Store = Store.STEAMRIP

    private val site: CustomStoreSite = SteamRipSite

    /** No accounts: these sites are anonymous, so the store is always "signed in". */
    override fun signIn(context: Context) = Unit

    override fun signOut(context: Context) = Unit

    override fun syncLibrary(context: Context, force: Boolean) {
        val app = context.applicationContext
        Thread({
            val items = runCatching { site.catalog(app) }
                .onFailure { StoresState.logLine("${store.label}: catalog failed (${it.javaClass.simpleName})") }
                .getOrDefault(emptyList())
            StoresState.post {
                if (items.isEmpty()) {
                    StoresState.problems[store] = "${store.label}: could not load the catalog"
                } else {
                    StoresState.problems.remove(store)
                    StoresState.library[store] = items
                }
            }
        }, "${store.id}-sync").start()
    }

    override fun loadShelves(context: Context, force: Boolean) {
        val app = context.applicationContext
        Thread({
            val featured = runCatching { site.featured(app) }.getOrDefault(emptyList())
            val shelves = com.droiddeck.launcher.stores.StoreShelves(trending = featured)
            StoresState.post { StoresState.shelves[store] = shelves }
        }, "${store.id}-shelves").start()
    }

    override fun search(context: Context, query: String): List<CatalogItem> =
        runCatching { site.search(context.applicationContext, query) }.getOrDefault(emptyList())

    override fun install(context: Context, item: CatalogItem, root: java.io.File) {
        val app = context.applicationContext
        val folder = StoreInstallRoot.folderFor(app, store, item.id, item.title, root)
        val entry = DownloadEntry(
            store = store,
            id = item.id,
            name = item.title,
            cover = item.imageUrl,
            bytesTotal = item.sizeBytes,
            location = StoreInstallRoot.labelFor(app, folder),
        )
        DownloadQueue.enqueue(app, entry) {
            CustomStoreInstaller(app, item, folder, site)
        }
    }

    override fun uninstall(context: Context, game: InstalledStoreGame) {
        // Files are removed by the host; nothing store-specific to clear.
    }
}
