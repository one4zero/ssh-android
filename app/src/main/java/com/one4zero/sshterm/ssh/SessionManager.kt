package com.one4zero.sshterm.ssh

import android.content.Context
import com.one4zero.sshterm.data.HostConfig

/** Holds all open SSH sessions. Accessed from the main thread only. */
object SessionManager {
    private val sessions = mutableListOf<SshSession>()
    private val listeners = mutableListOf<() -> Unit>()
    private var nextId = 1

    fun all(): List<SshSession> = sessions.toList()

    fun get(id: Int): SshSession? = sessions.firstOrNull { it.id == id }

    fun open(context: Context, host: HostConfig): SshSession {
        val session = SshSession(nextId++, host)
        sessions.add(session)
        SshService.start(context.applicationContext)
        session.start()
        notifyChanged()
        return session
    }

    fun close(context: Context, session: SshSession) {
        session.close()
        sessions.remove(session)
        SshService.refresh(context.applicationContext)
        notifyChanged()
    }

    fun closeAll(context: Context) {
        sessions.toList().forEach { it.close() }
        sessions.clear()
        SshService.refresh(context.applicationContext)
        notifyChanged()
    }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    fun notifyChanged() {
        listeners.toList().forEach { it() }
        SshService.refreshNotification()
    }
}
