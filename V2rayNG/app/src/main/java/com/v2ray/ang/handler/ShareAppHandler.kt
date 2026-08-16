package com.v2ray.ang.handler

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.io.File

/**
 * Hands the installed APK to whatever the user wants to send it with.
 *
 * This is a distribution feature, not a convenience one. There is no Play Store listing for
 * an app like this and there is unlikely ever to be one, so it travels the way such apps
 * actually travel where they are needed: a file passed between people in a chat. That route
 * already exists whether or not the app helps; helping means the copy that arrives is the
 * real one rather than whatever a re-upload site wrapped in an installer, because it came
 * from an install the recipient's friend was already running.
 *
 * It also happens to be the only route left during a shutdown, when nothing can be
 * downloaded from anywhere but two phones in a room can still reach each other.
 */
object ShareAppHandler {

    /**
     * The copy handed out. Named for the app and version rather than `base.apk`, which is
     * what `sourceDir` is called and what every recipient would otherwise see.
     */
    private const val SHARE_DIR = "share"

    /**
     * Builds a shareable copy of this APK and returns a send intent for it, or null when the
     * APK cannot be read — which happens on a split install, where there is no single file
     * that is the app.
     */
    fun buildShareIntent(context: Context): Intent? {
        val apk = copyOfInstalledApk(context) ?: return null

        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "ShareApp: could not expose the APK", e)
            return null
        }

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "v2rayV")
            // The link is included as well as the file. A file alone cannot be updated, and
            // somebody who receives one and likes it should be able to find the next version
            // without having to ask for it again.
            putExtra(Intent.EXTRA_TEXT, AppConfig.APP_URL)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * A copy of the running APK under a name worth receiving.
     *
     * Copied rather than shared in place because `sourceDir` lives in a directory no other
     * app may read, and because its filename is `base.apk` for every Android app that has
     * ever existed — a recipient looking at their downloads folder would have no way to tell
     * which one this was.
     */
    private fun copyOfInstalledApk(context: Context): File? = try {
        val source = File(context.applicationInfo.sourceDir)
        if (!source.isFile) {
            LogUtil.w(AppConfig.TAG, "ShareApp: no single APK to share from ${source.path}")
            null
        } else {
            val version = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull().orEmpty().ifBlank { "app" }

            val dir = File(context.cacheDir, SHARE_DIR)
            dir.mkdirs()
            // One name, overwritten each time, so repeated shares do not fill the cache with
            // thirty-megabyte copies of the same file.
            val target = File(dir, "v2rayV-$version.apk")
            source.copyTo(target, overwrite = true)
            target
        }
    } catch (e: Exception) {
        LogUtil.e(AppConfig.TAG, "ShareApp: could not copy the APK", e)
        null
    }
}
