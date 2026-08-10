package com.v2ray.ang.automode

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil

/**
 * Runs [SmartSwitch] where the traffic counters are actually read — in the core's process —
 * and carries out its verdict.
 *
 * It lived in the view model first, which worked but only while something was holding the
 * UI together. A VPN spends most of its life with the app closed, and "the connection died
 * and nothing noticed" is precisely the case this feature exists for, so a version that
 * needs the app open answers the easy half of the problem.
 *
 * Here it sits next to the one reader of the core's counters. Note that reader stops on
 * `ACTION_SCREEN_OFF`, which suits this exactly: with the screen off nobody is waiting for
 * a page, and a server that carries nothing at 4am has done nothing wrong.
 *
 * The switch itself reuses the path the notification's restart button already takes —
 * select a different server, then ask the service to restart onto it. Nothing new had to be
 * invented to change servers from here, which is the reason to do it this way.
 */
object SmartSwitchController {

    private var switcher: SmartSwitch? = null
    private var switcherGuid: String? = null

    /**
     * Feeds one tick of proxied traffic, in bytes per second.
     *
     * Called from the speed-notification loop, which already has these figures and is the
     * only thing allowed to read them — the core's counters reset when queried, so there
     * can only ever be one reader.
     */
    fun onTrafficSample(context: Context, upBytesPerSec: Long, downBytesPerSec: Long) {
        val store = try {
            AutoModeSourceManager.getStore()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "SmartSwitch: could not read settings", e)
            return
        }

        if (!store.smartSwitch) {
            switcher = null
            switcherGuid = null
            return
        }

        val guid = MmkvManager.getSelectServer()
        if (guid.isNullOrEmpty()) {
            return
        }

        if (guid != switcherGuid) {
            // A new server is judged against its own measurement, and starts with a clean
            // slate — evidence gathered through the last tunnel says nothing about this one.
            switcherGuid = guid
            switcher = SmartSwitch(referenceMbps = store.speedByGuid[guid] ?: 0.0)
                .also { it.reset(System.currentTimeMillis()) }
            return
        }

        val verdict = switcher?.onSample(upBytesPerSec, downBytesPerSec, System.currentTimeMillis())
        if (verdict is SmartSwitch.Verdict.Switch) {
            act(context, guid, verdict.reason)
        }
    }

    /** Forgets everything, so a tunnel that has just come up is not judged on the last one. */
    fun reset() {
        switcher = null
        switcherGuid = null
    }

    private fun act(context: Context, currentGuid: String, reason: String) {
        when (val next = AutoModeReserve.next(currentGuid)) {
            is AutoModeReserve.Next.Server -> {
                LogUtil.i(AppConfig.TAG, "SmartSwitch: $reason moving to ${next.position}/${next.total}")
                MmkvManager.setSelectServer(next.guid)
                switcherGuid = next.guid
                switcher = SmartSwitch(
                    referenceMbps = AutoModeSourceManager.getStore().speedByGuid[next.guid] ?: 0.0
                ).also { it.reset(System.currentTimeMillis()) }

                MessageHelper.sendMsg2UI(context, AppConfig.MSG_SMART_SWITCH, reason)
                // The same path the notification's restart button takes: stop, wait, start
                // again on whatever is now selected.
                MessageHelper.sendMsg2Service(context, AppConfig.MSG_STATE_RESTART, "")
            }

            AutoModeReserve.Next.Exhausted -> {
                // Deliberately does not start a run. A search is expensive and very visible,
                // and starting one unprompted from a background service — possibly while the
                // user is asleep — is not something to do on the strength of eight bad
                // seconds. The reserve being empty is worth saying; acting on it is theirs.
                LogUtil.i(AppConfig.TAG, "SmartSwitch: reserve exhausted, staying put")
                switcher?.reset(System.currentTimeMillis())
            }
        }
    }
}
