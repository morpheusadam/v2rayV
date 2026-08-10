package com.v2ray.ang.notice

/**
 * What the app should show in the slot at the bottom of the dashboard, if anything.
 *
 * Fetched from a small JSON document in the config repository. The default state — no
 * document, an empty one, or `notice: null` — is nothing at all, which is the point: the
 * slot exists so something *can* be said later, not so something is always being said.
 *
 * Deliberately dumb. It carries a title, a body and at most one button. It cannot run
 * code, render markup or open anything but a URL, because a channel that lets a remote
 * file decide what an installed VPN app does is a channel worth keeping narrow.
 */
data class NoticeDocument(
    var version: Int = 1,
    var notice: Notice? = null,
)

data class Notice(
    /**
     * Stable identifier. Dismissal is remembered against it, so editing the text of a
     * notice without changing its id will not bring it back for people who dismissed it —
     * and changing the id will.
     */
    var id: String = "",

    var title: String = "",
    var body: String = "",

    /** "green", "violet" or "red". Anything else falls back to green. */
    var accent: String = "green",

    var action: NoticeAction? = null,

    /**
     * Version range this notice applies to, by `versionCode`. Zero means unbounded.
     *
     * This is what stops an "update available" card from following users onto the version
     * it was telling them to install: set [maxVersionCode] to the last version that should
     * still see it.
     */
    var minVersionCode: Int = 0,
    var maxVersionCode: Int = 0,

    /** False for something that must not be swiped away, such as a security notice. */
    var dismissible: Boolean = true,
) {
    fun appliesTo(versionCode: Int): Boolean {
        if (id.isBlank()) {
            return false
        }
        if (minVersionCode > 0 && versionCode < minVersionCode) {
            return false
        }
        if (maxVersionCode > 0 && versionCode > maxVersionCode) {
            return false
        }
        return title.isNotBlank() || body.isNotBlank()
    }
}

/** What the notice's single button does. */
data class NoticeAction(
    var label: String = "",

    /**
     * - `install` — download [url] and hand it to the system package installer.
     * - `url` — open [url] in the browser.
     * - anything else — no button.
     */
    var type: String = "url",

    var url: String = "",
) {
    val isInstall: Boolean get() = type.equals("install", ignoreCase = true)
    val isOpenUrl: Boolean get() = type.equals("url", ignoreCase = true)
    val isUsable: Boolean
        get() = label.isNotBlank() && url.startsWith("https://") && (isInstall || isOpenUrl)
}
