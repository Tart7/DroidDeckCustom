package com.droiddeck.customstore

import android.content.Context
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.StoreArchiveUnpacker
import com.droiddeck.launcher.stores.StoreExe
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstalls
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.download.DownloadQueue
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Download job for a custom store: fetch the archive, unpack it, pick the .exe, then let
 * upstream write the install record. Uses the public download-job interface, so no
 * reflection is involved anywhere.
 */
class CustomStoreInstaller(
    private val context: Context,
    private val item: CatalogItem,
    private val folder: File,
    private val site: CustomStoreSite,
) : DownloadQueue.DownloadJob {

    private val cancelled = AtomicBoolean(false)

    override fun run(handle: DownloadQueue.JobHandle): String? {
        handle.stage(com.droiddeck.launcher.stores.download.DownloadStage.MANIFEST, "resolving")
        handle.log("${site.store.label}: resolving ${item.title}")

        val direct = site.resolveDownload(context, item, handle::log)
            ?: return "${site.store.label}: could not resolve the download URL"

        handle.stage(com.droiddeck.launcher.stores.download.DownloadStage.DOWNLOAD, "downloading")
        val scratch = File(folder.parentFile ?: context.cacheDir, "${folder.name}.partial").apply { mkdirs() }
        val archive = File(scratch, "${safe(item.title)}.zip")
        download(direct, archive, handle)
        if (cancelled.get()) return null

        handle.stage(com.droiddeck.launcher.stores.download.DownloadStage.VERIFY, "unpacking")
        folder.mkdirs()
        val unpacker = StoreArchiveUnpacker(context, cancelled, site.store.label, 0, 0, "custom-unpack")
        unpacker.work = folder
        unpacker.unpack(archive, archive.name, handle)

        val exe = StoreExe.pick(folder, null, item.title)
        if (exe.isBlank()) return "${site.store.label}: no .exe found in ${folder.name}"

        val sidecar = StoreGameSidecar(
            store = site.store,
            id = item.id,
            title = item.title,
            exe = exe,
            cover = item.imageUrl,
            hero = item.imageUrl,
            extra = item.extra,
            cloud = false,
            achievements = false,
        )
        StoreInstalls.complete(context, folder, sidecar) { done, total ->
            handle.stageProgress(done.toLong(), total.toLong())
        }

        runCatching { archive.delete(); scratch.deleteRecursively() }
        handle.log("${site.store.label}: installed ${item.title}")
        return folder.path
    }

    override fun cancel(deleteFiles: Boolean) {
        cancelled.set(true)
        if (deleteFiles) folder.deleteRecursively()
    }

    private fun download(url: String, target: File, handle: DownloadQueue.JobHandle) {
        target.parentFile?.mkdirs()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", CustomFetch.UA)
        // Fileditch/itsfileditchfiles returns 302 -> homepage without a site Referer.
        conn.setRequestProperty("Referer", SteamRipSite.BASE + "/")
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
        var done = 0L
        val buf = ByteArray(128 * 1024)
        try {
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    while (!cancelled.get()) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) handle.progress(done, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (cancelled.get()) target.delete()
    }

    private fun safe(title: String) = title.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "game" }
}
