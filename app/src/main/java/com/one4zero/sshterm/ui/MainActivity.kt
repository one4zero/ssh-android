package com.one4zero.sshterm.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toolbar
import com.one4zero.sshterm.App
import com.one4zero.sshterm.R
import com.one4zero.sshterm.data.HostConfig
import com.one4zero.sshterm.ssh.SessionManager
import com.one4zero.sshterm.ssh.SshSession

class MainActivity : Activity() {
    private val app get() = App.instance
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var quickInput: EditText
    private val adapter = Adapter()

    private val changeListener: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setActionBar(toolbar)
        setupEdgeToEdge(findViewById(R.id.root), toolbar)

        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        quickInput = findViewById(R.id.quick_input)
        list.adapter = adapter
        list.setOnItemClickListener { _, view, position, _ -> onItemClick(position, view) }
        list.setOnItemLongClickListener { _, view, position, _ ->
            showItemMenu(position, view)
            true
        }

        findViewById<View>(R.id.fab).setOnClickListener {
            startActivity(Intent(this, HostEditActivity::class.java))
        }
        quickInput.setText(app.settings.lastQuickConnect)
        quickInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                quickConnect()
                true
            } else false
        }
        findViewById<View>(R.id.quick_connect).setOnClickListener { quickConnect() }

        requestNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        SessionManager.addListener(changeListener)
        app.hosts.addListener(changeListener)
        refresh()
    }

    override fun onStop() {
        SessionManager.removeListener(changeListener)
        app.hosts.removeListener(changeListener)
        super.onStop()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !app.settings.notificationAsked &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            app.settings.notificationAsked = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_KEYS, 0, R.string.keys)
        menu.add(0, MENU_SNIPPETS, 1, R.string.snippets)
        menu.add(0, MENU_SETTINGS, 2, R.string.settings)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_KEYS -> startActivity(Intent(this, KeysActivity::class.java))
            MENU_SNIPPETS -> startActivity(Intent(this, SnippetsActivity::class.java))
            MENU_SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun quickConnect() {
        val text = quickInput.text.toString().trim()
        if (text.isEmpty()) {
            quickInput.error = getString(R.string.quick_connect_hint)
            return
        }
        val host = HostConfig.parseQuick(text)
        if (host == null) {
            quickInput.error = "Формат: user@host или user@host:port"
            return
        }
        app.settings.lastQuickConnect = text
        if (host.username.isBlank()) {
            askUsername { user -> openSession(host.copy(username = user)) }
        } else {
            openSession(host)
        }
    }

    private fun askUsername(onUser: (String) -> Unit) {
        val box = dialogContainer()
        val edit = formEdit(hint = getString(R.string.host_user), monospace = true)
        box.addView(edit)
        AlertDialog.Builder(this)
            .setTitle(R.string.host_user)
            .setView(box)
            .setPositiveButton(R.string.connect) { _, _ ->
                val u = edit.text.toString().trim()
                if (u.isNotEmpty()) onUser(u)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        edit.requestFocus()
    }

    private fun openSession(host: HostConfig) {
        val session = SessionManager.open(this, host)
        startActivity(Intent(this, TerminalActivity::class.java).putExtra(TerminalActivity.EXTRA_SESSION_ID, session.id))
    }

    private fun openExisting(session: SshSession) {
        startActivity(Intent(this, TerminalActivity::class.java).putExtra(TerminalActivity.EXTRA_SESSION_ID, session.id))
    }

    private fun onItemClick(position: Int, view: View) {
        when (val item = adapter.getItem(position)) {
            is Row.SessionRow -> openExisting(item.session)
            is Row.HostRow -> openSession(item.host)
            else -> Unit
        }
    }

    private fun showItemMenu(position: Int, anchor: View) {
        when (val item = adapter.getItem(position)) {
            is Row.SessionRow -> {
                val popup = PopupMenu(this, anchor)
                popup.menu.add(0, 1, 0, R.string.menu_disconnect)
                popup.setOnMenuItemClickListener {
                    SessionManager.close(this, item.session)
                    true
                }
                popup.show()
            }
            is Row.HostRow -> {
                val host = item.host
                val popup = PopupMenu(this, anchor)
                popup.menu.add(0, 1, 0, R.string.connect)
                popup.menu.add(0, 2, 1, R.string.edit)
                popup.menu.add(0, 3, 2, R.string.duplicate)
                popup.menu.add(0, 4, 3, R.string.delete)
                popup.setOnMenuItemClickListener { mi ->
                    when (mi.itemId) {
                        1 -> openSession(host)
                        2 -> startActivity(Intent(this, HostEditActivity::class.java).putExtra(HostEditActivity.EXTRA_HOST_ID, host.id))
                        3 -> {
                            val copy = host.copy(id = java.util.UUID.randomUUID().toString(), name = host.displayName + " (копия)", lastUsed = 0)
                            app.hosts.upsert(copy)
                        }
                        4 -> confirm(null, "Удалить «${host.displayName}»?", getString(R.string.delete)) {
                            app.hosts.delete(host.id)
                            app.history.clear(host.id)
                        }
                    }
                    true
                }
                popup.show()
            }
            else -> Unit
        }
    }

    private fun refresh() {
        val rows = mutableListOf<Row>()
        val sessions = SessionManager.all()
        if (sessions.isNotEmpty()) {
            rows.add(Row.Header(getString(R.string.active_sessions)))
            sessions.forEach { rows.add(Row.SessionRow(it)) }
        }
        val hosts = app.hosts.sorted()
        if (hosts.isNotEmpty()) {
            rows.add(Row.Header(getString(R.string.saved_servers)))
            hosts.forEach { rows.add(Row.HostRow(it)) }
        }
        adapter.rows = rows
        adapter.notifyDataSetChanged()
        empty.visibility = if (hosts.isEmpty() && sessions.isEmpty()) View.VISIBLE else View.GONE
    }

    private sealed class Row {
        class Header(val title: String) : Row()
        class SessionRow(val session: SshSession) : Row()
        class HostRow(val host: HostConfig) : Row()
    }

    private inner class Adapter : BaseAdapter() {
        var rows: List<Row> = emptyList()

        override fun getCount() = rows.size
        override fun getItem(position: Int): Row = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun isEnabled(position: Int) = rows[position] !is Row.Header

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            if (row is Row.Header) {
                val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_header, parent, false)
                (v as TextView).text = row.title
                return v
            }
            val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_host, parent, false)
            val title = v.findViewById<TextView>(R.id.title)
            val subtitle = v.findViewById<TextView>(R.id.subtitle)
            val status = v.findViewById<View>(R.id.status)
            val more = v.findViewById<ImageButton>(R.id.more)
            more.setOnClickListener { showItemMenu(position, it) }
            when (row) {
                is Row.SessionRow -> {
                    val s = row.session
                    title.text = s.terminalTitle ?: s.title
                    subtitle.text = "${s.host.address} — ${s.statusMessage}"
                    status.visibility = View.VISIBLE
                    status.background.mutate().setTint(
                        getColor(
                            when (s.state) {
                                SshSession.State.CONNECTED -> R.color.session_badge
                                SshSession.State.CONNECTING -> android.R.color.holo_orange_light
                                SshSession.State.DISCONNECTED -> R.color.session_badge_off
                            },
                        ),
                    )
                }
                is Row.HostRow -> {
                    title.text = row.host.displayName
                    subtitle.text = row.host.address
                    status.visibility = View.GONE
                }
                else -> Unit
            }
            return v
        }
    }

    companion object {
        private const val MENU_KEYS = 1
        private const val MENU_SNIPPETS = 2
        private const val MENU_SETTINGS = 3
    }
}
