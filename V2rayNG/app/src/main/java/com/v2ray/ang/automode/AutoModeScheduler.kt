package com.v2ray.ang.automode

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.AutoModeMessage
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import java.util.concurrent.TimeUnit
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
 *    is watching something would be felt immediately.
 *  - It does not run more often than the reserve actually decays. Free servers die in
 *    days, not minutes, so a daily refresh is the right order of magnitude and a
 *    fifteen-minute one would be a battery complaint waiting to happen.
 */
object AutoModeScheduler {

    private const val TASK_NAME = "automode_refresh"

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

    /** True when the reserve has run down far enough to be worth a run. */
    fun isReserveLow(): Boolean {
        val store = AutoModeSourceManager.reload()
        return reserveSize() < (store.reserveCount * LOW_WATER_FRACTION)
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
