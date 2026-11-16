package com.kienhoang.dualsubreplay.poliglot

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.kienhoang.dualsubreplay.R

/*
 * Poliglot's daily reminder: one inexact alarm a day at the time chosen on the page. The alarm and the
 * notification go to PoliglotReminder, which no other app can call. A restart clears alarms and the app has
 * no boot permission, so SHOKALON sets the alarm again whenever it opens, and after an app update.
 */

private const val CHANNEL = "poliglot_daily"
private const val NOTIFICATION_ID = 7301
private const val ALARM_REQUEST = 7302
private const val OPEN_REQUEST = 7303
private const val DEFAULT_STATUS = "Bugungi speaking mashqingiz kutyapti. 30 daqiqa ajrating!"

internal object PoliglotAlarm {
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL, "Poliglot: kunlik mashq eslatmasi", NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = "Har kuni speaking mashqini eslatadi"
        manager.createNotificationChannel(channel)
    }

    private fun pending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST,
            Intent(context, PoliglotReminder::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Sets (or, with no time chosen, cancels) the daily alarm from the saved time. */
    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pending(context)
        alarms.cancel(pending)
        val prefs = context.getSharedPreferences(POLIGLOT_PREFS, Context.MODE_PRIVATE)
        val hour = prefs.getInt("hour", -1)
        val minute = prefs.getInt("minute", 0)
        if (!isPoliglotClockTime(hour, minute)) return
        alarms.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            nextPoliglotReminder(System.currentTimeMillis(), hour, minute),
            AlarmManager.INTERVAL_DAY,
            pending,
        )
    }

    fun notify(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val status =
            context
                .getSharedPreferences(POLIGLOT_PREFS, Context.MODE_PRIVATE)
                .getString("status", null)
                ?.takeIf(String::isNotBlank) ?: DEFAULT_STATUS
        val open =
            Intent(context, PoliglotActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val content =
            PendingIntent.getActivity(
                context,
                OPEN_REQUEST,
                open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            Notification
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_poliglot_notif)
                .setContentTitle("Poliglot: mashq vaqti")
                .setContentText(status)
                .setStyle(Notification.BigTextStyle().bigText(status))
                .setAutoCancel(true)
                .setContentIntent(content)
                .build()
        context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }
}

/** The daily alarm: shows the reminder. Not exported, so only this app's alarm reaches it. */
class PoliglotReminder : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) = PoliglotAlarm.notify(context)
}

/** After an app update the alarm is gone; this sets it again. */
class PoliglotBootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            PoliglotAlarm.schedule(context)
        }
    }
}
