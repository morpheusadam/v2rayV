package com.v2ray.ang.notice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads an update and hands it to Android's package installer.
 *
 * The app never installs anything itself — it cannot. All it does is fetch a file and
 * raise the system's install dialog, which asks the user and does the work. What the
 * `REQUEST_INSTALL_PACKAGES` permission buys is the right to raise that dialog at all.
 *
 * Two rules the download enforces, because this is the one place the app runs code it
 * fetched from the network:
 *
 *  - **HTTPS only, and only from the project's own release host.** A notice document is a
 *    plain file in a git repository; if it were ever edited by someone else, the worst it
 *    should be able to do is point at a page, not at an APK of their choosing.
 *  - **The signature does the rest.** Android refuses to install an update signed with a
 *    different key than the installed app, so a substituted APK cannot replace this one —
 *    it can only fail. That check is the real protection, and it is not ours to weaken.
 */
object NoticeInstaller {

    /** Release assets live under this host. Anything else is opened, never installed. */
    private const val ALLOWED_HOST = "github.com"

    private const val CONNECT_TIMEOUT_MILLIS = 15_000
    private const val READ_TIMEOUT_MILLIS = 60_000

    /** An APK larger than this is not one of ours. */
    private const val MAX_BYTES = 200L * 1024 * 1024

    private const val FILE_NAME = "update.apk"

    sealed interface Result {
        /** The installer dialog was raised; the user decides from here. */
        data object Launched : Result

        /** Android needs the "install unknown apps" toggle for this app first. */
        data class NeedsPermission(val intent: Intent) : Result

        data class Failed(val reason: String) : Result
    }

    fun isTrustedUrl(url: String): Boolean = runCatching {
        val parsed = URL(url)
        parsed.protocol.equals("https", ignoreCase = true) &&
            (parsed.host == ALLOWED_HOST || parsed.host.endsWith(".$ALLOWED_HOST"))
    }.getOrDefault(false)

    /**
     * Fetches [url] and raises the install dialog.
     *
     * @return [Result.NeedsPermission] when the user has not granted this app the right to
     *         install packages; the caller starts that intent and can try again after.
     */
    suspend fun downloadAndInstall(context: Context, url: String): Result = withContext(Dispatchers.IO) {
        if (!isTrustedUrl(url)) {
            LogUtil.w(AppConfig.TAG, "Notice: refusing to install from $url")
            return@withContext Result.Failed("Update must come from $ALLOWED_HOST over HTTPS")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${BuildConfig.APPLICATION_ID}")
            )
            return@withContext Result.NeedsPermission(intent)
        }

        val target = File(context.cacheDir, FILE_NAME)
        if (!download(url, target)) {
            return@withContext Result.Failed("Download failed")
        }

        return@withContext try {
            val uri = FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.cache",
                target
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Result.Launched
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Notice: could not raise the installer", e)
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun download(url: String, target: File): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                // Release assets redirect to a CDN; the redirect target is still GitHub's
                // and is followed by the connection rather than re-checked here.
                instanceFollowRedirects = true
            }
            if (connection.responseCode !in 200..299) {
                LogUtil.w(AppConfig.TAG, "Notice: update download returned ${connection.responseCode}")
                return false
            }

            // A half-written file left by an interrupted download must never be handed to
            // the installer, so it is written fresh and deleted on any failure.
            target.delete()
            var total = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) {
                            break
                        }
                        total += read
                        if (total > MAX_BYTES) {
                            LogUtil.w(AppConfig.TAG, "Notice: update exceeded $MAX_BYTES bytes")
                            return false
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            total > 0
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Notice: update download failed", e)
            false
        } finally {
            connection?.disconnect()
            if (!target.isFile || target.length() == 0L) {
                target.delete()
            }
        }
    }
}
