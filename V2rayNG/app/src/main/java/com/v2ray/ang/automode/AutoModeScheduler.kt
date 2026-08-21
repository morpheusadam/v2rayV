package com.v2ray.ang.automode

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.AutoModeMessage
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.random.Random

/**
 * Keeps the reserve of ready servers stocked, so that only the very first connection is
 * ever slow.
 *
 * The reason a run takes minutes is that it is doing real work — downloading lists,
 * opening tunnels, timing downloads. None of that gets faster. What can change is *when*
 * it happens: done in the background while nothing is waiting on it, the same work costs
 * the user nothing, and the press of the power button becomes a lookup.
 *
 * Two things this deliberately does not do:
 *
 *  - It does not run while the tunnel is up. A run starts and stops a dozen throwaway
 *    cores and saturates the radio measuring throughput; doing that underneath a user who
 *    is watching something would be felt immediately. What it does instead is ask again as
 *    soon as the tunnel comes down — see [refreshAfterTunnelStops], without which a user
 *    who leaves the VPN on all day never gets a refresh at all.
 *  - It does not run more often than the reserve actually decays. Free servers die in
 *    days, not minutes, so a daily refresh is the right order of magnitude and a
 *    fifteen-minute one would be a battery complaint waiting to happen.
 */
object AutoModeScheduler {

    private const val TASK_NAME = "automode_refresh"

    /**
     * The catch-up run, armed when the tunnel comes down.
     *
     * A scheduled refresh is declined while the tunnel is up, and for the user this app is
     * for that is most of the time — the VPN is switched on in the morning and left on. The
     * periodic task would then fire four times a day and decline four times a day, and the
     * reserve would never be renewed no matter how long the phone was awake for. This is the
     * other end of that: the moment the tunnel stops is the moment a run is allowed again,
     * so that is when one is asked for.
     */
    private const val CATCHUP_TASK_NAME = "automode_refresh_catchup"

    /**
     * How long after the tunnel stops before the catch-up runs. Long enough that a restart —
     * the notification's button, a Smart Switch, a config edit — has already brought the
     * tunnel back up and the run declines itself, and short enough to still be the same
     * sitting for the user.
     */
    private const val CATCHUP_DELAY_MINUTES = 3L

    /**
     * The source lists are regenerated hourly, and free servers die in hours rather than
     * days, so a daily refresh would spend most of its life holding a reserve that no
     * longer works. Six hours keeps the reserve roughly current without waking the device
     * on the lists' own schedule, which would be a battery complaint waiting to happen.
     */
    private const val INTERVAL_HOURS = 6L

    /**
     * The window inside each interval that WorkManager may run the task in. A wide flex
     * period lets it be batched with whatever else the device wakes up for, which is the
     * difference between a background refresh and a background battery drain.
     */
    private const val FLEX_HOURS = 2L

    /** A reserve smaller than this is worth refreshing early. */
    private const val LOW_WATER_FRACTION = 0.5

    /**
     * How old the reserve may get before it is refreshed whatever its size.
     *
     * Counting the reserve is not a measure of whether it still works. Nothing removes a
     * server from it when it dies — the entries are replaced wholesale by the next run and
     * not before — so a reserve of ten dead servers counts as ten, the low-water test below
     * answers false, and every scheduled refresh from then on is declined. That
     * is the whole of the "it worked for the first few days" report: the servers a run left
     * behind are free servers, they die within days, and the schedule that was supposed to
     * replace them could never fire once it had been filled.
     *
     * Age is the honest signal, because it is the one that actually changes. A day is well
     * inside the lifetime of a public server and well outside the six-hour period, so a
     * reserve is refreshed roughly daily rather than never.
     */
    private const val MAX_AGE_HOURS = 24L

    /**
     * Schedules the refresh, keeping any existing schedule so that opening the app does
     * not push the next run further away every time.
     */
    fun schedule(context: Context = AngApplication.application) {
        val request = PeriodicWorkRequestBuilder<RefreshTask>(
            INTERVAL_HOURS, TimeUnit.HOURS,
            FLEX_HOURS, TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            // Spread across users and across days, so a fixed list of public sources is
            // not fetched by everyone at the same moment.
            .setInitialDelay(Random.nextLong(1, INTERVAL_HOURS), TimeUnit.HOURS)
            .addTag(TASK_NAME)
            .build()

        RemoteWorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(TASK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        LogUtil.i(AppConfig.TAG, "AutoMode: background refresh scheduled")
    }

    fun cancel(context: Context = AngApplication.application) {
        RemoteWorkManager.getInstance(context).cancelUniqueWork(TASK_NAME)
    }

    /** How many of the servers the reserve is supposed to hold are actually there. */
    fun reserveSize(): Int = MmkvManager.decodeServerList(AutoModeEngine.TOP_GROUP_ID)
        .count { MmkvManager.decodeServerConfig(it) != null }

    /** How long ago the reserve was last rebuilt, or [Long.MAX_VALUE] if it never was. */
    fun reserveAgeMillis(store: AutoModeStore): Long {
        if (store.reserveBuiltMillis <= 0) {
            return Long.MAX_VALUE
        }
        // A clock that has been moved backwards would otherwise make the reserve look like
        // it was built in the future and freeze the schedule again.
        return max(0L, System.currentTimeMillis() - store.reserveBuiltMillis)
    }

    /**
     * True when the reserve is worth rebuilding: it has run down, or it has simply got old.
     *
     * Both halves are needed. The size test catches a run that came back with almost
     * nothing; the age test catches the far more common case of a full reserve whose ten
     * servers have quietly stopped working, which the size test cannot see at all.
     */
    fun isRefreshDue(): Boolean {
        val store = AutoModeSourceManager.reload()
        if (reserveSize() < (store.reserveCount * LOW_WATER_FRACTION)) {
            return true
        }
        return reserveAgeMillis(store) >= TimeUnit.HOURS.toMillis(MAX_AGE_HOURS)
    }

    /**
     * Asks for a run shortly after the tunnel stops, if one is owed.
     *
     * Called from the core as it shuts down. It only ever enqueues the same request the
     * periodic task enqueues, and the receiving side applies the same two guards, so a
     * restart that brings the tunnel straight back up costs one declined wake-up and
     * nothing else.
     *
     * Off the caller's thread, because the caller is a service being destroyed on the main
     * thread and [isRefreshDue] reads the source store — several hundred links of JSON,
     * which is already known to be enough to hang a main thread. The application context is
     * taken here for the same reason: by the time this runs, the service that called it is
     * gone.
     */
    fun refreshAfterTunnelStops(context: Context) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!isRefreshDue()) {
                    return@launch
                }
                val request = OneTimeWorkRequestBuilder<RefreshTask>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .setRequiresBatteryNotLow(true)
                            .build()
                    )
                    .setInitialDelay(CATCHUP_DELAY_MINUTES, TimeUnit.MINUTES)
                    .addTag(CATCHUP_TASK_NAME)
                    .build()
                RemoteWorkManager.getInstance(app)
                    .enqueueUniqueWork(CATCHUP_TASK_NAME, ExistingWorkPolicy.REPLACE, request)
                LogUtil.i(AppConfig.TAG, "AutoMode: reserve is stale, refresh queued for after the tunnel stops")
            } catch (e: Exception) {
                // The tunnel is shutting down; nothing here is worth taking that path with it.
                LogUtil.w(AppConfig.TAG, "AutoMode: could not queue the catch-up refresh: ${e.message}")
            }
        }
    }

    /**
     * The periodic body. It only ever asks the existing run service to refresh — the whole
     * pipeline, including the foreground notification and the cancel button, is the same
     * one the button uses. A background refresh that behaved differently from a manual run
     * would be a second implementation to keep correct.
     *
     * Whether the refresh is actually worth doing is decided on the other side, in the
     * core's process, where the tunnel's state is readable.
     */
    class RefreshTask(context: Context, params: WorkerParameters) :
        CoroutineWorker(context, params) {

        override suspend fun doWork(): Result {
            LogUtil.i(AppConfig.TAG, "AutoMode: background refresh due")
            MessageHelper.sendMsg2AutoModeService(
                applicationContext,
                AutoModeMessage(AppConfig.MSG_AUTOMODE_REFRESH)
            )
            return Result.success()
        }
    }
}
