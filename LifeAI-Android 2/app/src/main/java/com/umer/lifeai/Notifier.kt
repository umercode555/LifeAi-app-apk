package com.umer.lifeai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** All channels are silent: sounds are played by [Sounds] so your custom MP3s work on every phone. */
object Notifier {
    const val CH_SVC = "svc"; const val CH_REMIND = "remind2"; const val CH_CALL = "calls2"
    private var n = 100

    fun channels(c: Context) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_SVC, "Background capture", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_REMIND, "AI reminders", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null); enableVibration(true) })
        nm.createNotificationChannel(NotificationChannel(CH_CALL, "AI calls", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null); enableVibration(true); vibrationPattern = longArrayOf(0, 600, 400, 600, 400, 600) })
    }

    fun remind(title: String, text: String) {
        val c = Cfg.app; channels(c)
        val pi = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val nt = NotificationCompat.Builder(c, CH_REMIND).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(pi).setAutoCancel(true).build()
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(n++, nt)
        Sounds.play("notif")
    }

    fun ring(callId: String, opening: String) {
        val c = Cfg.app; channels(c)
        val i = Intent(c, CallActivity::class.java).putExtra("call_id", callId).putExtra("opening", opening).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(c, callId.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val nt = NotificationCompat.Builder(c, CH_CALL).setSmallIcon(android.R.drawable.sym_action_call).setContentTitle("Life AI is calling").setContentText("Tap to answer")
            .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_HIGH).setFullScreenIntent(pi, true).setContentIntent(pi)
            .setOngoing(true).setTimeoutAfter(95_000).build()
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(2, nt)
        Sounds.play("ring", loop = true, maxMs = 95_000)
    }

    fun stopRing() { Sounds.stop("ring"); (Cfg.app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(2) }
}
