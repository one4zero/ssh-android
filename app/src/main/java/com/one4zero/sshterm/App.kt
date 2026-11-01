package com.one4zero.sshterm

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.one4zero.sshterm.data.HistoryStore
import com.one4zero.sshterm.data.HostStore
import com.one4zero.sshterm.data.KeyStoreRepo
import com.one4zero.sshterm.data.KnownHostsStore
import com.one4zero.sshterm.data.Settings
import com.one4zero.sshterm.data.SnippetStore

class App : Application() {
    lateinit var hosts: HostStore
        private set
    lateinit var keys: KeyStoreRepo
        private set
    lateinit var snippets: SnippetStore
        private set
    lateinit var knownHosts: KnownHostsStore
        private set
    lateinit var history: HistoryStore
        private set
    lateinit var settings: Settings
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        hosts = HostStore(this)
        keys = KeyStoreRepo(this)
        snippets = SnippetStore(this)
        knownHosts = KnownHostsStore(this)
        history = HistoryStore(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_SESSIONS,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_SESSIONS = "sessions"

        lateinit var instance: App
            private set
    }
}
