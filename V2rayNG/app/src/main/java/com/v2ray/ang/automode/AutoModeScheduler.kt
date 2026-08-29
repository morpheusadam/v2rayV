package com.v2ray.ang.automode

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteCoroutineWorker
import androidx.work.multiprocess.RemoteListenableWorker.ARGUMENT_CLASS_NAME
import androidx.work.multiprocess.RemoteListenableWorker.ARGUMENT_PACKAGE_NAME
import androidx.work.multiprocess.RemoteWorkManager
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.AutoModeMessage
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.service.AutoModeRemoteWorkerService
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

    /** Unique name for the pulse, separate so the two can be scheduled and cancelled apart. */
    private const val PULSE_TASK_NAME = "automode_pulse"

    /**
     * How often the reserve is asked whether it still works.
     *
     * Four times as often as the refresh it informs, because it costs a few kilobytes
     * rather than a few hundred megabytes, and because the thing it is watching for — a
     * batch of free servers dying together — happens on the scale of hours.
     */
    private const val PULSE_INTERVAL_HOURS = 3L

    /** As with the refresh, a wide window so the work batches with whatever else wakes the device. */
    private const val PULSE_FLEX_HOURS = 1L

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
                    // 🔴 Unmetered, not merely connected.
                    //
                    // A run is not a small job. It fetches source bodies measured in
                    // megabytes and then downloads from up to nineteen servers for six
                    // seconds each; the realistic cost is a few hundred megabytes, and the
                    // ceiling is close to a gigabyte. Nobody asked for it — that is what
                    // makes it a background refresh — so it must not be paid for out of
                    // someone's mobile allowance, and Play's Device and Network Abuse
                    // policy says the same thing in its own words about data transfer the
                    // user did not initiate.
                    //
                    // The cost of this is real and worth stating: a user with no wifi gets
                    // no background refresh. What they do still get is the pulse, which is
                    // kilobytes and runs on any network — so the reserve is still *known*
                    // to be dead, and the next press is a run the user asked for.
                    .setRequiredNetworkType(NetworkType.UNMETERED)
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

    /**
     * Schedules the reserve pulse — the cheap half of keeping servers ready.
     *
     * Separate from the refresh, and on a shorter period, because the two cost completely
     * different things. A refresh is a full run: minutes, and hundreds of megabytes. A
     * pulse is one short request per kept server, so it can run four times as often
     * without being worth a battery complaint, and it is what makes the refresh's own
     * decision an informed one.
     *
     * It carries no unmetered constraint for the same reason. Kilobytes on someone's
     * mobile data to find out whether their servers still work is a trade nobody would
     * refuse, and requiring wifi would leave the very users this app exists for — whose
     * phone may never see a trusted wifi network — with no maintenance at all.
     */
    fun schedulePulse(context: Context = AngApplication.application) {
        val request = PeriodicWorkRequestBuilder<PulseTask>(
            PULSE_INTERVAL_HOURS, TimeUnit.HOURS,
            PULSE_FLEX_HOURS, TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setInitialDelay(Random.nextLong(1, PULSE_INTERVAL_HOURS), TimeUnit.HOURS)
            .addTag(PULSE_TASK_NAME)
            // Names the process the work is to be executed in. Without it a
            // RemoteCoroutineWorker has nothing to bind to and the job fails on its own
            // arguments rather than on anything it was asked to do.
            .setInputData(
                Data.Builder()
                    .putString(ARGUMENT_PACKAGE_NAME, context.packageName)
                    .putString(ARGUMENT_CLASS_NAME, AutoModeRemoteWorkerService::class.java.name)
                    .build()
            )
            .build()

        RemoteWorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PULSE_TASK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        LogUtil.i(AppConfig.TAG, "AutoMode: reserve pulse scheduled")
    }

    fun cancel(context: Context = AngApplication.application) {
        RemoteWorkManager.getInstance(context).cancelUniqueWork(TASK_NAME)
        RemoteWorkManager.getInstance(context).cancelUniqueWork(PULSE_TASK_NAME)
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

        // Rows first, because a reserve that came back nearly empty is worth refreshing
        // whatever a pulse would say about the few servers in it.
        //
        // 🔴 Measured against `topCount`, not `reserveCount`. `reserveCount` is a field
        // nothing in the app has ever written — it is declared, read here, and permanently
        // 10. The size the user actually chose is `topCount`, which the keep-count slider
        // writes and which `selectWinners` takes. So someone who set it to three got a
        // reserve of three, which this read as "seven short of ten", and paid for a full
        // run every six hours forever on a reserve that was exactly the size they asked for.
        val wanted = store.topCount.coerceAtLeast(1)
        if (reserveSize() < (wanted * LOW_WATER_FRACTION)) {
            return true
        }

        // Then liveness, which is the test the count could never be. A full reserve of
        // servers that have all quietly died counts as full; only a pulse can tell the
        // difference, and once one has run this stops waiting out the age backstop before
        // acting on what it already knows.
        if (store.reserveCheckedMillis > 0 && !AutoModePulse.isReserveHealthy(store)) {
            return true
        }

        // The backstop stays. A reserve that has never been pulsed — a fresh install, a
        // device where the pulse never got to run — still gets refreshed on age alone,
        // which is the behaviour that existed before any of this and is the honest answer
        // when nothing better is known.
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
                            // Same reasoning as the periodic request: the tunnel stopping
                            // is a user action, but this run is not the one they took.
                            .setRequiredNetworkType(NetworkType.UNMETERED)
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

    /**
     * The pulse body. Same shape as [RefreshTask] and for the same reason — the work has
     * to happen in the core's process, because it starts throwaway cores, and this worker
     * runs in `:bg`.
     *
     * Unlike the refresh it is not declined while the tunnel is up. That is the point of
     * it: the app excludes itself from its own VPN, so a ping goes over the real network
     * even with the tunnel running, and a user who never turns the VPN off is otherwise a
     * user whose reserve is never checked at all.
     */
    class PulseTask(context: Context, params: WorkerParameters) :
        RemoteCoroutineWorker(context, params) {

        override suspend fun doRemoteWork(): Result {
            LogUtil.i(AppConfig.TAG, "AutoMode: reserve pulse due")
            return try {
                // Already in the core's process by the time this runs, so the native
                // library can simply be initialised rather than reached for. Idempotent:
                // the daemon may well have done it already.
                CoreNativeManager.initCoreEnv(applicationContext)
                AutoModePulse.run(applicationContext)
                Result.success()
            } catch (e: Exception) {
                // Retrying would spend a wake-up on a reserve that is about to be rebuilt
                // anyway: a pulse that did not happen reads as "not known to be alive",
                // which is already the conclusion a failed one would have reached.
                LogUtil.w(AppConfig.TAG, "AutoMode: reserve pulse failed: ${e.message}")
                Result.success()
            }
        }
    }
}
