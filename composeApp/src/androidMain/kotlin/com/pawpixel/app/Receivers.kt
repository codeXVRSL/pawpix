package com.pawpixel.app

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.pawpixel.core.Reminder
import com.pawpixel.core.ReminderRef
import com.pawpixel.core.StateOps
import com.pawpixel.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Runs [block] off the main thread while keeping the broadcast alive until it finishes. */
private fun BroadcastReceiver.work(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.Default).launch {
        try { block() } finally { pending.finish() }
    }
}

/** Shows a care reminder; its "Done" action marks the task complete without opening the app. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK) ?: return
        // What the notification is about: one task, or several when bundled ("Mochi: feed and fresh
        // water"), each with the planned time it was for.
        val refs = intent.getStringExtra(EXTRA_REFS)?.let(ReminderRef::decodeAll)?.ifEmpty { null } ?: listOf(ReminderRef(taskId, null))
        val id = intent.getIntExtra(EXTRA_ID, 0)
        val nm = context.getSystemService(NotificationManager::class.java)
        if (intent.action == ACTION_DONE) {
            nm.cancel(id)
            work { PawPixelApplication.repo(context).completeFromReminder(refs) }
            return
        }
        // A note that isn't about a task (Rabies Awareness Month): just show it.
        if (taskId.isEmpty()) {
            work { if (PawPixelApplication.repo(context).state.value.settings.remindersEnabled) show(context, intent, taskId, emptyList(), id) }
            return
        }
        work {
            val repo = PawPixelApplication.repo(context)
            // Family sharing: fetch the others' taps first, so nobody is told to feed a pet that was just fed.
            if (repo.family.household != null) repo.family.syncWithin(8_000)
            // Only what's still to do (by anyone); nothing left, no notification.
            val state = repo.state.value
            val open = refs.filter { r ->
                val t = state.task(r.taskId)
                t != null && t.remindersOn && !StateOps.isCovered(state, r, repo.now(), repo.clock)
            }
            if (open.isEmpty() || !state.settings.remindersEnabled) return@work
            show(context, intent, open.first().taskId, open, id)
        }
    }

    private fun show(context: Context, intent: Intent, taskId: String, refs: List<ReminderRef>, id: Int) {
        val nm = context.getSystemService(NotificationManager::class.java)
        AndroidPlatform.ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val done = PendingIntent.getBroadcast(
            context, id,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_DONE).putExtra(EXTRA_TASK, taskId)
                .putExtra(EXTRA_REFS, ReminderRef.encodeAll(refs)).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, AndroidPlatform.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_paw)
            .setContentTitle(intent.getStringExtra(EXTRA_TITLE))
            .setContentText(intent.getStringExtra(EXTRA_BODY))
            .setStyle(NotificationCompat.BigTextStyle().bigText(intent.getStringExtra(EXTRA_BODY)))
            .setContentIntent(open)
            .setAutoCancel(true)
        if (intent.getBooleanExtra(EXTRA_QUICK_DONE, true)) builder.addAction(0, tr("Done"), done)
        val notification = builder.build()
        runCatching { nm.notify(id, notification) } // no-op if notification permission was denied
    }

    companion object {
        const val ACTION_SHOW = "com.pawpixel.app.REMIND"
        const val ACTION_DONE = "com.pawpixel.app.DONE"
        const val EXTRA_TASK = "task"
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val EXTRA_QUICK_DONE = "quickDone"
        const val EXTRA_REFS = "refs"

        /** Same request code and action => same PendingIntent, so it can be cancelled later. */
        fun pendingIntent(context: Context, id: Int, r: Reminder?): PendingIntent {
            val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_SHOW)
            if (r != null) intent.putExtra(EXTRA_TASK, r.taskId).putExtra(EXTRA_ID, r.id)
                .putExtra(EXTRA_TITLE, r.title).putExtra(EXTRA_BODY, r.body).putExtra(EXTRA_QUICK_DONE, r.quickDone)
                .putExtra(EXTRA_REFS, ReminderRef.encodeAll(r.refs))
            return PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}

/** Fires when the pet's mood is due to change, so the widget updates without the app open. */
class WidgetTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = work {
        val repo = PawPixelApplication.repo(context)
        repo.family.syncWithin(8_000) // the family's taps, so the widget's mood is everyone's care
        repo.publish()
    }

    companion object {
        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 1, Intent(context, WidgetTickReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** Alarms are cleared on reboot, app update and time zone change; reschedule everything. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = work { PawPixelApplication.repo(context).publish() }
}
