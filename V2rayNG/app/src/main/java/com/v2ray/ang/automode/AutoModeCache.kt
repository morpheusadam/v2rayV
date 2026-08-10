package com.v2ray.ang.automode

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.io.File

/**
 * Keeps the last list that was successfully fetched, so a blocked network falls back on
 * something recent instead of something shipped.
 *
 * The lists in `v2ray-config` are rebuilt every day. The app already reads them live, so
 * ordinary use has never needed a new build — but the copies inside the APK did, because
 * they are frozen at whatever the assets held when it was compiled. Those copies are the
 * final rung of the route ladder, reached only when every route to GitHub has failed, and
 * a snapshot from three months ago is close to useless there: the proxies in it are long
 * dead, and the whole point of that rung is to find one that is not.
 *
 * So every successful fetch is written here, and the ladder gains a rung:
 *
 *     network  →  this cache  →  the APK's assets
 *
 * After a single successful run the app is carrying yesterday's list rather than the build
 * date's, whatever happens to the network afterwards. The bundled assets then only ever
 * matter for a phone that has never once managed a fetch — a first run on an already
 * blocked network, which is the one case they were written for.
 *
 * Deliberately no expiry. A cached list is stale eventually, but it is stale *later* than
 * the one it would fall back to, and on this rung the alternative to a stale list is no
 * list at all.
 */
object AutoModeCache {

    private const val DIR = "automode"

    /** Ignore a body far too small to be a list; a truncated fetch is worse than none. */
    private const val MIN_USEFUL_BYTES = 256

    private fun file(context: Context, name: String) = File(File(context.filesDir, DIR), name)

    /**
     * Stores [body] under [name], replacing whatever was there.
     *
     * Failure is logged and swallowed: this is an optimisation on a fallback path, and a
     * full disk must not be able to break a run that has already fetched what it needed.
     */
    fun put(context: Context, name: String, body: String?) {
        if (body == null || body.length < MIN_USEFUL_BYTES) {
            return
        }
        try {
            val target = file(context, name)
            target.parentFile?.mkdirs()
            // Written beside and moved into place, so a kill mid-write cannot leave a
            // half-file that parses into a handful of servers and looks like a real list.
            val temp = File(target.parentFile, "${target.name}.tmp")
            temp.writeText(body)
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "AutoMode: could not cache $name: ${e.message}")
        }
    }

    /** The cached copy, or null when there is not one worth using. */
    fun get(context: Context, name: String): String? {
        return try {
            val file = file(context, name)
            if (!file.isFile || file.length() < MIN_USEFUL_BYTES) {
                return null
            }
            file.readText()
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "AutoMode: could not read cached $name: ${e.message}")
            null
        }
    }

    /** How old the cached copy is in whole days, or null when there is none. */
    fun ageDays(context: Context, name: String): Long? {
        val file = file(context, name)
        if (!file.isFile) {
            return null
        }
        return (System.currentTimeMillis() - file.lastModified()) / (24L * 60 * 60 * 1000)
    }
}
