// MergeSMS Sender - based on android-masssmssender
// Copyright (c) 2025 Jacob "Isreal" Eiler and Isreal Consulting, LLC.
// Modifications Copyright (c) 2026 MergeSMS Sender contributors.
// Licensed under the GNU Affero General Public License v3 or later.

package com.mergesms.sender

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class SendService : Service() {

    companion object {
        const val ACTION_CANCEL = "com.mergesms.sender.CANCEL"
        const val ACTION_SENT = "com.mergesms.sender.SMS_SENT"
        private const val EXTRA_BATCH = "batch"
        private const val EXTRA_INDEX = "idx"
        private const val CHANNEL = "send_progress"
        private const val NOTIF_ID = 42
        /** Pause between recipients to be gentle on the carrier and the Android SMS rate limiter. */
        private const val DELAY_MS = 1200L
        private const val RESULT_WAIT_MS = 60_000L
    }

    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val batch = SendState.current ?: return
            if (intent.getLongExtra(EXTRA_BATCH, -1L) != batch.id) return
            val idx = intent.getIntExtra(EXTRA_INDEX, -1)
            val item = batch.items.getOrNull(idx) ?: return
            synchronized(batch) {
                item.partsDone++
                if (resultCode != Activity.RESULT_OK) item.error = errorText(resultCode)
                if (item.partsDone >= item.parts) {
                    item.status = if (item.error == null) Item.SENT else Item.FAILED
                }
            }
            SendState.notifyChanged()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(ACTION_SENT), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            SendState.cancelRequested = true
            return START_NOT_STICKY
        }
        val batch = SendState.current
        if (batch == null || worker?.isAlive == true) {
            if (worker?.isAlive != true) stopSelf()
            return START_NOT_STICKY
        }

        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification("Starting…", 0, batch.items.size, true), type
        )

        worker = Thread {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mergesms:send").apply {
                acquire(3 * 60 * 60 * 1000L)
            }
            try {
                runBatch(batch)
            } catch (e: Exception) {
                batch.state = Batch.INTERRUPTED
            } finally {
                try {
                    HistoryStore(this).save(batch)
                } catch (e: Exception) {
                }
                SendState.running = false
                SendState.notifyChanged()
                if (wakeLock?.isHeld == true) wakeLock?.release()
                finishNotification(batch)
                stopSelf()
            }
        }.also { it.start() }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java)
        else SmsManager.getDefault()

    private fun runBatch(batch: Batch) {
        val texts = SendState.texts
        val sms = smsManager()
        val store = HistoryStore(this)
        store.save(batch)
        val total = batch.items.size

        for ((idx, item) in batch.items.withIndex()) {
            if (SendState.cancelRequested) break
            try {
                val parts = sms.divideMessage(texts[idx])
                synchronized(batch) {
                    item.parts = parts.size
                    item.status = Item.SENDING
                }
                val sentIntents = ArrayList<PendingIntent>()
                for (p in parts.indices) {
                    val i = Intent(ACTION_SENT)
                        .setPackage(packageName)
                        .putExtra(EXTRA_BATCH, batch.id)
                        .putExtra(EXTRA_INDEX, idx)
                    sentIntents.add(
                        PendingIntent.getBroadcast(
                            this, idx * 10 + p, i,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                }
                sms.sendMultipartTextMessage(item.phone, null, parts, sentIntents, null)
            } catch (e: Exception) {
                synchronized(batch) {
                    item.error = e.message ?: "Send error"
                    item.status = Item.FAILED
                }
            }
            val done = batch.sent + batch.failed
            notificationManager().notify(
                NOTIF_ID, buildNotification("Sent $done of $total", done, total, true)
            )
            SendState.notifyChanged()
            if ((idx + 1) % 25 == 0) store.save(batch)
            try {
                Thread.sleep(DELAY_MS)
            } catch (e: InterruptedException) {
                break
            }
        }

        // Wait for the system to report results for messages still in flight.
        val deadline = System.currentTimeMillis() + RESULT_WAIT_MS
        while (System.currentTimeMillis() < deadline &&
            batch.items.any { it.status == Item.SENDING }
        ) {
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                break
            }
        }
        synchronized(batch) {
            batch.state = if (SendState.cancelRequested) Batch.CANCELLED else Batch.DONE
        }
    }

    private fun finishNotification(batch: Batch) {
        val text = "${batch.stateLabel()}: ${batch.sent} sent, ${batch.failed} failed"
        notificationManager().notify(
            NOTIF_ID, buildNotification(text, batch.sent + batch.failed, batch.items.size, false)
        )
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            notificationManager().createNotificationChannel(
                NotificationChannel(CHANNEL, "Sending progress", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String, done: Int, total: Int, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val nb = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("MergeSMS Sender")
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
        if (ongoing) {
            val cancel = PendingIntent.getService(
                this, 1,
                Intent(this, SendService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE
            )
            nb.setProgress(total, done, false).addAction(0, "Cancel", cancel)
        }
        return nb.build()
    }

    private fun errorText(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Generic failure"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "No service"
        SmsManager.RESULT_ERROR_NULL_PDU -> "Null PDU"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "Radio off"
        5 -> "Sending limit exceeded"
        else -> "Error $code"
    }
}
