package com.v2ray.ang.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.automode.AutoModeEngine
import com.v2ray.ang.automode.AutoModePulse
import com.v2ray.ang.automode.AutoModeScheduler
import com.v2ray.ang.automode.AutoModeStage
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.dto.AutoModeMessage
import com.v2ray.ang.dto.AutoModeProgressMessage
import com.v2ray.ang.dto.AutoModeSpeedMessage
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Hosts one Auto Mode run.
 *
 * A run takes minutes and keeps working while the user is elsewhere, so it belongs in a
 * foreground service rather than a screen's lifecycle. It lives in the core's own process
 * for the same reason the other test services do: the tunnel tests and the throwaway
 * speed-test cores all need libv2ray, and it is only loaded there.
 */
class AutoModeRunService : Service() {

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let(AppLocaleManager::localizedContext))
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    @Volatile
    private var engine: AutoModeEngine? = null

    /**
     * Whether this run has already connected the user to a server it had not measured. Read
     * when a measured one turns up, to tell a first connection from a replacement.
     */
    @Volatile
    private var connectedProvisionally = false

    @Volatile
    private var lastMessage: String = ""

    @Volatile
    private var lastRemainingMillis: Long = 0

    @Volatile
    private var lastStage: AutoModeStage = AutoModeStage.MEASURING

    private val cancelAction by lazy {
        val intent = Intent(this, AutoModeRunService::class.java)
            .putExtra("content", AutoModeMessage(AppConfig.MSG_AUTOMODE_CANCEL))
        val pendingIntent = PendingIntent.getService(
            this,
            NotificationChannelType.AUTO_MODE.notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        NotificationCompat.Action.Builder(
            R.drawable.ic_stop_24dp,
            getString(R.string.action_cancel),
            pendingIntent
        ).build()
    }

    override fun onCreate() {
        super.onCreate()
        CoreNativeManager.initCoreEnv(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        LogUtil.i(AppConfig.TAG, "AutoModeRunService is being destroyed")
        engine?.stop()
        serviceJob.cancel()
        NotificationHelper.stopForeground(this)
        NotificationHelper.cancel(NotificationChannelType.AUTO_MODE, this)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val message = intent?.serializable<AutoModeMessage>("content")
        if (message == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (message.key) {
            AppConfig.MSG_AUTOMODE_START -> handleStart()
            AppConfig.MSG_AUTOMODE_REFRESH -> handleScheduledRefresh(startId)
            AppConfig.MSG_AUTOMODE_PULSE -> handlePulse(startId)
            AppConfig.MSG_AUTOMODE_CANCEL -> handleCancel()
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    /**
     * A refresh nobody asked for has to earn the right to run. It is declined when the
     * tunnel is up — a run starts a dozen throwaway cores and saturates the radio, which
     * a user mid-stream would feel — and when the reserve is neither short nor stale,
     * because the point of the schedule is to keep the reserve stocked, not to keep it
     * churning.
     *
     * The tunnel case is deferred rather than dropped: the core arms a catch-up as it shuts
     * down, so a user who leaves the VPN on all day still gets a refresh at the first moment
     * one is allowed. Without that, declining here meant declining forever.
     *
     * Both checks live here rather than in the worker because this is the core's process,
     * the only one where the core's state can be read without loading the native library
     * into the UI.
     */
    private fun handleScheduledRefresh(startId: Int) {
        // Before anything that reads the store. isRefreshDue() calls reload(), which
        // replaces the process-wide cached store with a fresh object off disk — and a run
        // in flight is holding a reference to the old one, writing its source health into
        // it for minutes before saving. Reloading underneath it means the run saves the
        // replacement instead and everything it learned is dropped, silently.
        if (AutoModeEngine.isRunInFlight()) {
            LogUtil.i(AppConfig.TAG, "AutoMode: a run is already in flight, skipping the scheduled refresh")
            return
        }
        if (CoreServiceManager.isRunning()) {
            LogUtil.i(AppConfig.TAG, "AutoMode: tunnel is up, deferring scheduled refresh")
            stopSelf(startId)
            return
        }
        if (!AutoModeScheduler.isRefreshDue()) {
            LogUtil.i(
                AppConfig.TAG,
                "AutoMode: reserve holds ${AutoModeScheduler.reserveSize()} and is current, skipping scheduled refresh"
            )
            stopSelf(startId)
            return
        }
        handleStart()
    }

    /**
     * Checks whether the kept servers still answer. Cheap, and quiet.
     *
     * Three things make this different from the scheduled refresh above:
     *
     * It runs **with the tunnel up**. That is not an oversight — it is most of the value.
     * The refresh declines while the tunnel is running and relies on the catch-up armed
     * when it stops, which means a user with always-on VPN never got any background
     * maintenance at all. The app excludes itself from its own VPN, so the throwaway cores
     * a ping starts go over the real network regardless.
     *
     * It **posts no foreground notification**. The run's notification exists so that a
     * minutes-long, hundreds-of-megabytes job is visible and cancellable. A few seconds of
     * kilobytes is not something to wake a user at 3 a.m. to tell them about.
     *
     * It **defers to a run in progress** rather than the other way round. A pulse and a run
     * both start throwaway cores in this process; the run is the one somebody is waiting
     * for, and the pulse loses nothing by being skipped — the run is about to replace the
     * reserve it would have measured.
     */
    private fun handlePulse(startId: Int) {
        if (engine != null || AutoModeEngine.isRunInFlight()) {
            // 🔴 Deliberately NOT stopSelf(startId).
            //
            // `stopSelf(int)` stops the service whenever the id it is given is the most
            // recent one delivered — and a pulse arriving during a run IS the most recent
            // one. So the guard that exists to protect the run was destroying it:
            // onDestroy calls engine?.stop() and cancels the service scope, so a run three
            // minutes and a couple of hundred megabytes in was thrown away, and the user
            // was shown a failure.
            //
            // Worse, the pulse is asked for on every app open, which made "open the app
            // while a background refresh happens to be running" — an ordinary thing to do —
            // the way to kill it.
            //
            // Returning leaves the run owning the service, which is right: it will call
            // stopSelf() itself when it finishes.
            LogUtil.i(AppConfig.TAG, "AutoMode: a run is in flight, skipping the pulse")
            return
        }
        serviceScope.launch {
            try {
                AutoModePulse.run(this@AutoModeRunService)
            } catch (e: Exception) {
                // Nothing downstream depends on a pulse having happened: an absent result
                // reads as "not known to be alive", which is the same conclusion a failed
                // one would reach.
                LogUtil.w(AppConfig.TAG, "AutoMode: pulse failed: ${e.message}")
            } finally {
                stopSelf(startId)
            }
        }
    }

    private fun handleStart() {
        NotificationHelper.startForeground(
            this,
            NotificationChannelType.AUTO_MODE,
            getString(R.string.app_name),
            getString(R.string.automode_running),
            cancelAction
        )

        // A second tap while a run is in flight must not start a competing pipeline: the
        // two would import into the same scratch groups and delete each other's servers.
        if (engine != null) {
            LogUtil.i(AppConfig.TAG, "AutoMode: run already in progress, ignoring start")
            publishProgress(true, lastMessage, lastRemainingMillis)
            return
        }

        val runEngine = AutoModeEngine(
            context = this,
            onProgress = { text ->
                lastMessage = text
                publishProgress(true, text, lastRemainingMillis)
                NotificationHelper.updateNotification(
                    channelType = NotificationChannelType.AUTO_MODE,
                    context = this,
                    title = getString(R.string.app_name),
                    content = text
                )
            },
            onEstimate = { remaining ->
                lastRemainingMillis = remaining
                publishProgress(true, lastMessage, remaining)
            },
            onStage = { stage ->
                lastStage = stage
                publishProgress(true, lastMessage, lastRemainingMillis)
            },
            onSpeedSample = { mbps, baseline ->
                MessageHelper.sendMsg2UI(
                    this,
                    AppConfig.MSG_AUTOMODE_SPEED,
                    JsonUtil.toJson(AutoModeSpeedMessage(mbps, baseline))
                )
            },
            onFirstAcceptable = { guid ->
                // Selecting here rather than in the UI process: the run already owns this
                // decision, and the store is multi-process, so a UI that is not on screen
                // does not stop the tunnel from becoming connectable.
                MmkvManager.setSelectServer(guid)
                // If the run already put the user on something unmeasured to end the wait,
                // this is a replacement for it rather than a first connection, and the two
                // mean opposite things to a tunnel that is already up.
                val what =
                    if (connectedProvisionally) AppConfig.MSG_AUTOMODE_UPGRADE
                    else AppConfig.MSG_AUTOMODE_READY
                MessageHelper.sendMsg2UI(this, what, guid)
            },
            onFirstUsable = { guid ->
                connectedProvisionally = true
                // Same path as a measured winner on purpose. Whether the tunnel actually
                // comes up is the UI's decision either way — it only connects unprompted for
                // a run the power button started — and routing the unmeasured server through
                // a second mechanism would give that decision two places to be wrong.
                MmkvManager.setSelectServer(guid)
                MessageHelper.sendMsg2UI(this, AppConfig.MSG_AUTOMODE_READY, guid)
            }
        )
        engine = runEngine

        serviceScope.launch {
            val result = try {
                runEngine.run()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "AutoMode: run threw", e)
                null
            }

            engine = null
            val finalMessage = result?.message?.takeIf { it.isNotBlank() }
                ?: getString(R.string.automode_failed)
            publishFinish(finalMessage)
            NotificationHelper.stopForeground(this@AutoModeRunService)
            stopSelf()
        }
    }

    private fun handleCancel() {
        LogUtil.i(AppConfig.TAG, "AutoMode: cancel requested")
        engine?.stop()
        // The engine checks its stop flag between stages, so the run winds itself down and
        // still gets to clean up its scratch groups. Killing the service here would leave
        // several hundred throwaway profiles behind.
        publishProgress(true, getString(R.string.automode_stopping), 0)
    }

    private fun publishProgress(running: Boolean, message: String, remainingMillis: Long) {
        MessageHelper.sendMsg2UI(
            this,
            AppConfig.MSG_AUTOMODE_PROGRESS,
            JsonUtil.toJson(
                AutoModeProgressMessage(running, message, remainingMillis, lastStage.name)
            )
        )
    }

    private fun publishFinish(message: String) {
        MessageHelper.sendMsg2UI(this, AppConfig.MSG_AUTOMODE_FINISH, message)
    }
}
