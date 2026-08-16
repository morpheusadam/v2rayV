package com.v2ray.ang.handler

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil

/**
 * Asks Android to stop suspending this app in the background.
 *
 * The single most common complaint about clients of this kind is that the tunnel dies while
 * the phone is in a pocket. Usually nothing is wrong with the tunnel: Doze suspends the
 * process, the connection is torn down, and the user finds out when they next unlock and
 * nothing loads. Manufacturers with aggressive power management make it worse, and several
 * of them are the most common phones among this app's users.
 *
 * Android exposes exactly one way to ask, and it is a system dialog the user answers.
 * Nothing here can grant the exemption on its own, which is the correct design: an app that
 * could exempt itself would be an app every app exempts itself.
 */
object BatteryOptimizationHandler {

    /** Whether Android has already been told to leave this app alone. */
    fun isExempt(context: Context): Boolean = try {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        power?.isIgnoringBatteryOptimizations(context.packageName) == true
    } catch (e: Exception) {
        LogUtil.e(AppConfig.TAG, "Battery: could not read the optimisation state", e)
        false
    }

    /**
     * The intent to raise, or null when there is nothing useful to show.
     *
     * Two different intents on purpose. When the app is still optimised, the direct request
     * puts a yes/no dialog in front of the user and is over in one tap. When it is already
     * exempt, that dialog does not appear at all, so the settings list is opened instead:
     * somebody who taps this having already granted it is checking, and an intent that does
     * nothing visible reads as a broken button.
     */
    fun buildIntent(context: Context): Intent? = try {
        if (isExempt(context)) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )
        }
    } catch (e: Exception) {
        LogUtil.e(AppConfig.TAG, "Battery: could not build the request intent", e)
        null
    }
}
