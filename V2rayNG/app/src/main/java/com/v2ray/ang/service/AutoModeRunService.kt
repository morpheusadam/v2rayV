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
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.AutoModeMessage
import com.v2ray.ang.dto.AutoModeProgressMessage
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.AppLocaleManager
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

    @Volatile
    private var lastMessage: String = ""

    @Volatile
    private var lastRemainingMillis: Long = 0

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
            AppConfig.MSG_AUTOMODE_CANCEL -> handleCancel()
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
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
            JsonUtil.toJson(AutoModeProgressMessage(running, message, remainingMillis))
        )
    }

    private fun publishFinish(message: String) {
        MessageHelper.sendMsg2UI(this, AppConfig.MSG_AUTOMODE_FINISH, message)
    }
}
