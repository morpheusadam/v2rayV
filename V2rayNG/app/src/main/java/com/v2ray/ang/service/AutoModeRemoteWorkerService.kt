package com.v2ray.ang.service

import androidx.work.multiprocess.RemoteWorkerService

/**
 * Lets a WorkManager job run **inside the core's process** instead of asking it to start a
 * service there.
 *
 * 🔴 This exists to get around a background-start rule, and the rule is the whole reason
 * the scheduled work was unreliable.
 *
 * The scheduler's workers run in `:bg`. Anything they want done that needs libv2ray has to
 * happen in `:RunSoLibV2RayDaemon`, because that is the only process the native library is
 * loaded into — and the only way to reach it was to start `AutoModeRunService` there. But a
 * job is not a foreground state: on Android 8 a plain `startService` from the background
 * throws outright, and since Android 12 `startForegroundService` throws
 * `ForegroundServiceStartNotAllowedException` unless the app holds one of a short list of
 * exemptions, of which "a WorkManager job is running" is not one. `MessageHelper` catches
 * and logs that, so the failure was silent: the work was scheduled, the worker ran, the
 * message went nowhere, and nothing in the app could tell the difference between that and a
 * refresh that had decided it was not needed.
 *
 * Declaring this service in the daemon process makes WorkManager bind to it and execute the
 * worker there, under its own job and its own wakelock. Nothing is started, so there is
 * nothing to be refused.
 *
 * It has no body of its own: `RemoteWorkerService` is complete, and the manifest entry —
 * specifically its `android:process` — is the entire content of this class.
 */
class AutoModeRemoteWorkerService : RemoteWorkerService()
