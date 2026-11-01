package com.orion.assistant.service.call

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.orion.assistant.OrionApplication
import com.orion.assistant.ui.call.IncomingCallActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CallState {
    IDLE,
    RINGING,
    ACTIVE,
    ENDED
}

object CallManager {
    private const val CALL_NOTIFICATION_ID = 1001

    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private var activeRingtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    /**
     * Triggers an incoming call notification and brings up the full-screen call UI.
     */
    fun triggerIncomingCall(context: Context, callerName: String = "Orion AI", reason: String = "Voice conversation requested") {
        if (_callState.value == CallState.RINGING || _callState.value == CallState.ACTIVE) return

        _callState.value = CallState.RINGING
        startRinging(context)

        val fullScreenIntent = Intent(context, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("CALLER_NAME", callerName)
            putExtra("CALL_REASON", reason)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            0,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, OrionApplication.CALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("Incoming call from $callerName")
            .setContentText(reason)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(CALL_NOTIFICATION_ID, notification)

        // Launch directly if app is in foreground or screen is on
        context.startActivity(fullScreenIntent)
    }

    fun answerCall(context: Context) {
        stopRinging(context)
        _callState.value = CallState.ACTIVE
        dismissNotification(context)
    }

    fun endCall(context: Context) {
        stopRinging(context)
        _callState.value = CallState.ENDED
        dismissNotification(context)
        _callState.value = CallState.IDLE
    }

    private fun startRinging(context: Context) {
        try {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            activeRingtone = RingtoneManager.getRingtone(context, ringtoneUri)
            activeRingtone?.play()

            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            val pattern = longArrayOf(0, 1000, 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (_: Exception) {}
    }

    private fun stopRinging(context: Context) {
        try {
            activeRingtone?.stop()
            activeRingtone = null
            vibrator?.cancel()
            vibrator = null
        } catch (_: Exception) {}
    }

    private fun dismissNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(CALL_NOTIFICATION_ID)
    }
}
