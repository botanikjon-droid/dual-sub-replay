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
 * Poliglot's daily reminders: in the morning about the day's tasks, in the evening about the report, each one
 * inexact alarm a day at the time chosen on the page. Alarms and notifications go to PoliglotReminder, which no
 * other app can call. A restart clears alarms and the app has no boot permission, so SHOKALON sets them again
 * whenever it opens, and after an app update.
 */

private const val CHANNEL = "poliglot_daily"
private const val OPEN_REQUEST = 7303
private const val EXTRA_SLOT = "slot"

/** One of the two daily reminders, with its saved time, text and fallback text. */
internal enum class PoliglotSlot(
    val hourKey: String,
    val minuteKey: String,
    val textKey: String,
    val title: String,
    val fallback: String,
    val alarmRequest: Int,
    val notificationId: Int,
) {
    MORNING(
        "hour",
        "minute",
        "status",
        "Poliglot: bugungi vazifa",
        "Bugungi hikoya va vazifalar kutyapti. 30 daqiqa ajrating!",
        7302,
        7301,
    ),
    EVENING("eveHour", "eveMinute", "eveStatus", "Poliglot: kechki hisobot", "Kechki hisobotni to'ldirib, Claude'ga yuboring.", 7304, 7305),
}

internal object PoliglotAlarm {
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL, "Poliglot: kunlik eslatmalar", NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = "Ertalab vazifa, kechqurun hisobot haqida eslatadi"
        manager.createNotificationChannel(channel)
    }

    private fun pending(
        context: Context,
        slot: PoliglotSlot,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            slot.alarmRequest,
            Intent(context, PoliglotReminder::class.java).putExtra(EXTRA_SLOT, slot.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Sets (or, with no time chosen, cancels) both daily alarms from the saved times. */
    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val prefs = context.getSharedPreferences(POLIGLOT_PREFS, Context.MODE_PRIVATE)
        PoliglotSlot.entries.forEach { slot ->
            val pending = pending(context, slot)
            alarms.cancel(pending)
            val hour = prefs.getInt(slot.hourKey, -1)
            val minute = prefs.getInt(slot.minuteKey, 0)
            if (isPoliglotClockTime(hour, minute)) {
                alarms.setInexactRepeating(
                    AlarmManager.RTC_WAKEUP,
                    nextPoliglotReminder(System.currentTimeMillis(), hour, minute),
                    AlarmManager.INTERVAL_DAY,
                    pending,
                )
            }
        }
    }

    fun notify(
        context: Context,
        slot: PoliglotSlot,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val text =
            context
                .getSharedPreferences(POLIGLOT_PREFS, Context.MODE_PRIVATE)
                .getString(slot.textKey, null)
                ?.takeIf(String::isNotBlank) ?: slot.fallback
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
                .setContentTitle(slot.title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(content)
                .build()
        context.getSystemService(NotificationManager::class.java)?.notify(slot.notificationId, notification)
    }
}

/** A daily alarm: shows its reminder. Not exported, so only this app's alarm reaches it. */
class PoliglotReminder : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val slot = PoliglotSlot.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_SLOT) } ?: PoliglotSlot.MORNING
        PoliglotAlarm.notify(context, slot)
    }
}

/** After an app update the alarms are gone; this sets them again. */
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
