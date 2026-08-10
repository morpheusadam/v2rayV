package com.v2ray.ang.notice

import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.automode.AutoModeNetwork
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one way this app can be told something after it has been installed.
 *
 * There is no server behind it — a JSON file in the same repository the subscription
 * catalog comes from, fetched over the same route ladder, so it reaches a phone on a
 * network that blocks GitHub for the same reasons the rest of Auto Mode does.
 *
 * Everything about it fails to nothing. No file, an unreachable network, malformed JSON,
 * a notice aimed at other versions, one already dismissed — every one of those paths ends
 * with the slot empty and the dashboard looking exactly as it does today. That matters
 * more than it sounds: a banner that appears when it should not is worse than no banner,
 * because it appears in a VPN app on someone's phone.
 */
object NoticeManager {

    private const val ID_NOTICE = "NOTICE"
    private const val KEY_CACHED = "CACHED"
    private const val KEY_DISMISSED = "DISMISSED"
    private const val KEY_FETCHED_AT = "FETCHED_AT"

    /** Don't re-fetch more often than this; the file changes rarely. */
    private const val TTL_MILLIS = 6L * 60 * 60 * 1000

    private val storage by lazy { MMKV.mmkvWithID(ID_NOTICE, MMKV.MULTI_PROCESS_MODE) }

    /**
     * The notice to show right now, or null.
     *
     * Reads the cached copy — this is called on every trip to the dashboard and must not
     * touch the network. [refresh] is what goes and looks.
     */
    fun current(): Notice? {
        val cached = storage.decodeString(KEY_CACHED)?.takeIf { it.isNotBlank() } ?: return null
        val document = runCatching {
            JsonUtil.fromJsonSafe(cached, NoticeDocument::class.java)
        }.getOrNull() ?: return null

        val notice = document.notice ?: return null
        if (!notice.appliesTo(BuildConfig.VERSION_CODE)) {
            return null
        }
        if (notice.dismissible && isDismissed(notice.id)) {
            return null
        }
        return notice
    }

    /**
     * Fetches the document unless the cached one is still fresh.
     *
     * A failure leaves the previous copy in place rather than clearing it: a notice that
     * vanishes because the phone was briefly offline would be worse than one that lingers
     * a few hours past its edit.
     */
    suspend fun refresh(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!force && System.currentTimeMillis() - storage.decodeLong(KEY_FETCHED_AT, 0) < TTL_MILLIS) {
            return@withContext
        }

        val body = AutoModeNetwork.fetchText(AutoModeNetwork.DEFAULT_NOTICE_URL)
        if (body.isNullOrBlank()) {
            LogUtil.d(AppConfig.TAG, "Notice: nothing fetched, keeping what is cached")
            return@withContext
        }

        // Parsed before it is stored, so a malformed edit upstream cannot replace a good
        // cached copy with something current() will only fail on later.
        val parsed = runCatching { JsonUtil.fromJsonSafe(body, NoticeDocument::class.java) }.getOrNull()
        if (parsed == null) {
            LogUtil.w(AppConfig.TAG, "Notice: document did not parse, keeping what is cached")
            return@withContext
        }

        storage.encode(KEY_CACHED, body)
        storage.encode(KEY_FETCHED_AT, System.currentTimeMillis())
        LogUtil.i(AppConfig.TAG, "Notice: refreshed, showing=${parsed.notice?.id ?: "none"}")
    }

    fun dismiss(id: String) {
        if (id.isBlank()) {
            return
        }
        val dismissed = storage.decodeStringSet(KEY_DISMISSED)?.toMutableSet() ?: mutableSetOf()
        dismissed.add(id)
        storage.encode(KEY_DISMISSED, dismissed)
    }

    fun isDismissed(id: String): Boolean =
        storage.decodeStringSet(KEY_DISMISSED)?.contains(id) == true

    /** Test seam and a way back for a user who dismissed something they wanted. */
    fun clearDismissals() {
        storage.remove(KEY_DISMISSED)
    }
}
