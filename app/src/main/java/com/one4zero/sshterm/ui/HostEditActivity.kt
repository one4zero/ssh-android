package com.one4zero.sshterm.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.Toolbar
import com.one4zero.sshterm.App
import com.one4zero.sshterm.R
import com.one4zero.sshterm.data.AuthType
import com.one4zero.sshterm.data.HostConfig
import com.one4zero.sshterm.data.SecretBox
import com.one4zero.sshterm.ssh.SessionManager

class HostEditActivity : Activity() {
    private val app get() = App.instance
    private var original: HostConfig? = null
    private var clearSavedPassword = false

    private lateinit var hostEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var userEdit: EditText
    private lateinit var nameEdit: EditText
    private lateinit var passwordEdit: EditText
    private lateinit var startupEdit: EditText
    private lateinit var authPassword: RadioButton
    private lateinit var authKey: RadioButton
    private lateinit var keySpinner: Spinner
    private var keyIds: List<String?> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_host_edit)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setActionBar(toolbar)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        setupEdgeToEdge(findViewById(R.id.root), toolbar)

        hostEdit = findViewById(R.id.host)
        portEdit = findViewById(R.id.port)
        userEdit = findViewById(R.id.username)
        nameEdit = findViewById(R.id.name)
        passwordEdit = findViewById(R.id.password)
        startupEdit = findViewById(R.id.startup)
        authPassword = findViewById(R.id.auth_password)
        authKey = findViewById(R.id.auth_key)
        keySpinner = findViewById(R.id.key_spinner)

        original = intent.getStringExtra(EXTRA_HOST_ID)?.let { app.hosts.get(it) }
        title = getString(if (original == null) R.string.host_new else R.string.host_edit)

        original?.let { h ->
            hostEdit.setText(h.host)
            portEdit.setText(h.port.toString())
            userEdit.setText(h.username)
            nameEdit.setText(h.name)
            startupEdit.setText(h.startupCommand)
            if (h.authType == AuthType.KEY) authKey.isChecked = true else authPassword.isChecked = true
        }
        updateSavedPasswordRow()

        findViewById<View>(R.id.clear_password).setOnClickListener {
            clearSavedPassword = true
            updateSavedPasswordRow()
        }
        findViewById<android.widget.RadioGroup>(R.id.auth_group).setOnCheckedChangeListener { _, _ -> updateAuthBlocks() }
        findViewById<View>(R.id.manage_keys).setOnClickListener {
            startActivity(Intent(this, KeysActivity::class.java))
        }
        findViewById<View>(R.id.save_connect).setOnClickListener {
            val host = collect() ?: return@setOnClickListener
            app.hosts.upsert(host)
            val session = SessionManager.open(this, host)
            startActivity(Intent(this, TerminalActivity::class.java).putExtra(TerminalActivity.EXTRA_SESSION_ID, session.id))
            finish()
        }
        updateAuthBlocks()
    }

    override fun onResume() {
        super.onResume()
        loadKeys()
    }

    private fun loadKeys() {
        val keys = app.keys.all()
        val selected = (keyIds.getOrNull(keySpinner.selectedItemPosition)) ?: original?.keyId
        keyIds = listOf<String?>(null) + keys.map { it.id }
        val labels = listOf("— выберите ключ —") + keys.map { "${it.name} (${it.type})" }
        keySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val idx = keyIds.indexOf(selected)
        keySpinner.setSelection(if (idx >= 0) idx else if (keys.size == 1) 1 else 0)
    }

    private fun updateAuthBlocks() {
        val key = authKey.isChecked
        findViewById<View>(R.id.password_block).visibility = if (key) View.GONE else View.VISIBLE
        findViewById<View>(R.id.key_block).visibility = if (key) View.VISIBLE else View.GONE
    }

    private fun updateSavedPasswordRow() {
        val hasSaved = original?.passwordEnc != null && !clearSavedPassword
        findViewById<View>(R.id.saved_password_row).visibility = if (hasSaved) View.VISIBLE else View.GONE
    }

    private fun collect(): HostConfig? {
        val host = hostEdit.text.toString().trim()
        if (host.isEmpty()) {
            hostEdit.error = "Укажите хост"
            hostEdit.requestFocus()
            return null
        }
        val port = portEdit.text.toString().trim().ifEmpty { "22" }.toIntOrNull()
        if (port == null || port !in 1..65535) {
            portEdit.error = "1–65535"
            portEdit.requestFocus()
            return null
        }
        val user = userEdit.text.toString().trim()
        if (user.isEmpty()) {
            userEdit.error = "Укажите пользователя"
            userEdit.requestFocus()
            return null
        }
        val auth = if (authKey.isChecked) AuthType.KEY else AuthType.PASSWORD
        val keyId = keyIds.getOrNull(keySpinner.selectedItemPosition)
        if (auth == AuthType.KEY && keyId == null) {
            toast("Выберите ключ или создайте новый в «Управление ключами»")
            return null
        }
        val newPassword = passwordEdit.text.toString()
        val passwordEnc = when {
            newPassword.isNotEmpty() -> SecretBox.encrypt(newPassword)
            clearSavedPassword -> null
            else -> original?.passwordEnc
        }
        val base = original ?: HostConfig()
        return base.copy(
            name = nameEdit.text.toString().trim(),
            host = host,
            port = port,
            username = user,
            authType = auth,
            passwordEnc = if (auth == AuthType.PASSWORD) passwordEnc else original?.passwordEnc?.takeIf { !clearSavedPassword },
            keyId = if (auth == AuthType.KEY) keyId else base.keyId,
            startupCommand = startupEdit.text.toString().trim(),
        )
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_SAVE, 0, R.string.save).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        if (original != null) menu.add(0, MENU_DELETE, 1, R.string.delete)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            MENU_SAVE -> {
                val host = collect() ?: return true
                app.hosts.upsert(host)
                finish()
            }
            MENU_DELETE -> original?.let { h ->
                confirm(null, "Удалить «${h.displayName}»?", getString(R.string.delete)) {
                    app.hosts.delete(h.id)
                    app.history.clear(h.id)
                    finish()
                }
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    companion object {
        const val EXTRA_HOST_ID = "host_id"
        private const val MENU_SAVE = 1
        private const val MENU_DELETE = 2
    }
}
