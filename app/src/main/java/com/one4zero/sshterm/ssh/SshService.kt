package com.one4zero.sshterm.ssh

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.one4zero.sshterm.App
import com.one4zero.sshterm.R
import com.one4zero.sshterm.ui.MainActivity
import com.one4zero.sshterm.ui.TerminalActivity

/** Foreground service which keeps SSH connections alive while the app is in the background. */
class SshService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = this
    }

    override fun onDestroy() {
        running = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT_ALL) {
            SessionManager.closeAll(this)
        }
        val count = SessionManager.all().size
        // startForeground must always be called after startForegroundService()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, buildNotification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(this))
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
        }
        if (count == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // keep running: sessions stay open when the app is swiped away
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        private const val TAG = "SshService"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT_ALL = "com.one4zero.sshterm.DISCONNECT_ALL"

        @Volatile
        private var running: SshService? = null

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, SshService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Unable to start foreground service", e)
            }
        }

        /** Stops the service when there are no sessions left. */
        fun refresh(context: Context) {
            if (SessionManager.all().isEmpty()) {
                running?.let {
                    it.stopForeground(STOP_FOREGROUND_REMOVE)
                    it.stopSelf()
                }
            } else {
                refreshNotification()
            }
        }

        fun refreshNotification() {
            val service = running ?: return
            if (SessionManager.all().isEmpty()) return
            val nm = service.getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification(service))
        }

        private fun buildNotification(context: Context): Notification {
            val sessions = SessionManager.all()
            val connected = sessions.count { it.state == SshSession.State.CONNECTED }
            val title = when (sessions.size) {
                0 -> context.getString(R.string.app_name)
                1 -> sessions[0].title
                else -> context.getString(R.string.notification_sessions, sessions.size)
            }
            val text = if (sessions.size == 1) {
                sessions[0].statusMessage
            } else {
                context.getString(R.string.notification_connected, connected, sessions.size)
            }
            val openIntent = if (sessions.size == 1) {
                Intent(context, TerminalActivity::class.java)
                    .putExtra(TerminalActivity.EXTRA_SESSION_ID, sessions[0].id)
            } else {
                Intent(context, MainActivity::class.java)
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val contentIntent = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val disconnectIntent = PendingIntent.getService(
                context, 1,
                Intent(context, SshService::class.java).setAction(ACTION_DISCONNECT_ALL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Builder(context, App.CHANNEL_SESSIONS)
                .setSmallIcon(R.drawable.ic_stat_terminal)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(
                    Notification.Action.Builder(null, context.getString(R.string.disconnect_all), disconnectIntent).build(),
                )
                .build()
        }
    }
}
