package com.ahmed.vidloader.util

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.widget.Toast
import com.ahmed.vidloader.R
import com.ahmed.vidloader.data.ScheduledJob
import com.ahmed.vidloader.data.ScheduledJobsRepository
import com.ahmed.vidloader.work.WorkScheduler
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * «جدولة التنزيل» (v1.10): framework date + time pickers, then the job is
 * persisted and its one-time WorkManager request registered. A trigger in
 * the past is rejected with a clear message and nothing is stored.
 */
object SchedulePicker {

    /** Shows date then time pickers; [onPicked] receives a future trigger time in millis. */
    fun pick(context: Context, onPicked: (Long) -> Unit) {
        val now = Calendar.getInstance()
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                TimePickerDialog(
                    context,
                    { _, hourOfDay, minute ->
                        val trigger = Calendar.getInstance().apply {
                            set(year, month, dayOfMonth, hourOfDay, minute, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                        if (trigger <= System.currentTimeMillis()) {
                            Toast.makeText(context, R.string.schedule_past, Toast.LENGTH_LONG).show()
                        } else {
                            onPicked(trigger)
                        }
                    },
                    now.get(Calendar.HOUR_OF_DAY),
                    now.get(Calendar.MINUTE),
                    false
                ).show()
            },
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    /** Pick a time and schedule ONE download of [url] at the quick preset. */
    fun schedule(context: Context, url: String, title: String) {
        pick(context) { triggerAt ->
            val job = ScheduledJob(url = url, title = title, triggerAtMillis = triggerAt)
            ScheduledJobsRepository(context).add(job)
            WorkScheduler.enqueueScheduled(context, job)
            val whenText = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(triggerAt))
            Toast.makeText(
                context,
                context.getString(R.string.scheduled_for, whenText),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Pick a time once and schedule every URL in [urls] for that same time. */
    fun scheduleAll(context: Context, urls: List<String>) {
        if (urls.isEmpty()) return
        pick(context) { triggerAt ->
            val repo = ScheduledJobsRepository(context)
            for (url in urls) {
                val job = ScheduledJob(url = url, title = "", triggerAtMillis = triggerAt)
                repo.add(job)
                WorkScheduler.enqueueScheduled(context, job)
            }
            val whenText = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(triggerAt))
            Toast.makeText(
                context,
                context.getString(R.string.scheduled_for, whenText),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
