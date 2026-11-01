package com.one4zero.sshterm.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toolbar
import com.one4zero.sshterm.App
import com.one4zero.sshterm.R
import com.one4zero.sshterm.data.SecretBox
import com.one4zero.sshterm.data.SshKeyEntry
import com.one4zero.sshterm.ssh.SshKeys

class KeysActivity : Activity() {
    private val app get() = App.instance
    private val adapter = Adapter()
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setActionBar(toolbar)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.keys)
        setupEdgeToEdge(findViewById(R.id.root), toolbar)

        empty = findViewById(R.id.empty)
        empty.text = getString(R.string.key_none)
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> showPublicKey(adapter.getItem(position)) }
        list.setOnItemLongClickListener { _, view, position, _ ->
            showKeyMenu(adapter.getItem(position), view)
            true
        }
        val fab = findViewById<ImageButton>(R.id.fab)
        fab.contentDescription = getString(R.string.key_generate)
        fab.setOnClickListener { showAddMenu(it) }
        refresh()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun refresh() {
        adapter.items = app.keys.all()
        adapter.notifyDataSetChanged()
        empty.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showAddMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Создать Ed25519 (рекомендуется)")
        popup.menu.add(0, 2, 1, "Создать RSA 4096")
        popup.menu.add(0, 3, 2, R.string.key_import_paste)
        popup.menu.add(0, 4, 3, R.string.key_import_file)
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> askName { generate(it, rsa = false) }
                2 -> askName { generate(it, rsa = true) }
                3 -> showImportDialog(clipboardText()?.takeIf { t -> t.contains("PRIVATE KEY") } ?: "", "")
                4 -> openFile()
            }
            true
        }
        popup.show()
    }

    private fun defaultKeyName(): String = "android-" + (Build.MODEL ?: "phone").replace(' ', '-').lowercase()

    private fun askName(onName: (String) -> Unit) {
        val box = dialogContainer()
        box.addView(formLabel(getString(R.string.key_name)))
        val edit = formEdit(value = defaultKeyName(), monospace = true)
        box.addView(edit)
        AlertDialog.Builder(this)
            .setTitle(R.string.key_generate)
            .setView(box)
            .setPositiveButton(R.string.key_generate) { _, _ -> onName(edit.text.toString().trim().ifEmpty { defaultKeyName() }) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun generate(name: String, rsa: Boolean) {
        val progress = ProgressDialog(this).apply {
            setMessage(getString(R.string.key_generating))
            setCancelable(false)
            show()
        }
        Thread {
            val result = runCatching {
                if (rsa) SshKeys.generateRsa(4096, name) else SshKeys.generateEd25519(name)
            }
            runOnUiThread {
                progress.dismiss()
                result.onSuccess { key ->
                    val entry = SshKeyEntry(
                        name = name,
                        type = key.info.type,
                        publicKey = key.info.publicKey,
                        fingerprint = key.info.fingerprint,
                        privateKeyEnc = SecretBox.encrypt(key.privateKey),
                    )
                    app.keys.add(entry)
                    refresh()
                    showPublicKey(entry)
                }.onFailure { e -> showError("Не удалось создать ключ: ${e.message}") }
            }
        }.start()
    }

    private fun openFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(intent, REQUEST_FILE)
        } catch (e: Exception) {
            toast("Нет приложения для выбора файлов")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FILE || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val text = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                if (bytes.size > 256 * 1024) throw IllegalArgumentException("Файл слишком большой")
                String(bytes, Charsets.UTF_8)
            }
        }.getOrElse {
            showError("Не удалось прочитать файл: ${it.message}")
            return
        } ?: return
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "key"
        showImportDialog(text, name)
    }

    private fun showImportDialog(keyText: String, name: String) {
        val box = dialogContainer()
        box.addView(formLabel(getString(R.string.key_name)))
        val nameEdit = formEdit(value = name.ifEmpty { "imported-key" })
        box.addView(nameEdit)
        box.addView(formLabel("Приватный ключ (-----BEGIN … PRIVATE KEY-----)"))
        val keyEdit = formEdit(value = keyText, multiLine = true)
        box.addView(keyEdit)
        box.addView(formLabel(getString(R.string.key_passphrase)))
        val passEdit = formEdit(secret = true)
        box.addView(passEdit)
        val scroll = android.widget.ScrollView(this).apply { addView(box) }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.key_import)
            .setView(scroll)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = keyEdit.text.toString().trim() + "\n"
                val pass = passEdit.text.toString().ifEmpty { null }
                val info = try {
                    SshKeys.parse(key, pass, nameEdit.text.toString().trim())
                } catch (e: SshKeys.KeyException) {
                    keyEdit.error = e.message
                    return@setOnClickListener
                } catch (e: Exception) {
                    keyEdit.error = e.message ?: "Ошибка"
                    return@setOnClickListener
                }
                val entry = SshKeyEntry(
                    name = nameEdit.text.toString().trim().ifEmpty { "key" },
                    type = info.type,
                    publicKey = info.publicKey,
                    fingerprint = info.fingerprint,
                    privateKeyEnc = SecretBox.encrypt(key),
                    passphraseEnc = if (info.encrypted && pass != null) SecretBox.encrypt(pass) else null,
                    encrypted = info.encrypted,
                )
                app.keys.add(entry)
                refresh()
                dialog.dismiss()
                toast("Ключ импортирован")
            }
        }
        dialog.show()
    }

    private fun showPublicKey(key: SshKeyEntry) {
        val tv = TextView(this).apply {
            text = key.publicKey
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(dp(22), dp(8), dp(22), dp(8))
        }
        val hint = TextView(this).apply {
            text = getString(R.string.key_install_hint) + "\n\n" + key.fingerprint
            textSize = 13f
            setTextColor(currentTextColorSecondary())
            setPadding(dp(22), dp(4), dp(22), dp(8))
        }
        val box = dialogContainer().apply {
            setPadding(0, 0, 0, 0)
            addView(tv)
            addView(hint)
        }
        AlertDialog.Builder(this)
            .setTitle(key.name)
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton(R.string.copy) { _, _ -> copyToClipboard(key.publicKey) }
            .setNeutralButton("Поделиться") { _, _ -> share(key) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun share(key: SshKeyEntry) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, key.publicKey)
            putExtra(Intent.EXTRA_SUBJECT, "${key.name}.pub")
        }
        startActivity(Intent.createChooser(send, getString(R.string.key_share_public)))
    }

    private fun showKeyMenu(key: SshKeyEntry, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, R.string.key_copy_public)
        popup.menu.add(0, 2, 1, R.string.key_share_public)
        popup.menu.add(0, 3, 2, "Переименовать")
        popup.menu.add(0, 4, 3, R.string.delete)
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> copyToClipboard(key.publicKey)
                2 -> share(key)
                3 -> rename(key)
                4 -> confirm(null, "Удалить ключ «${key.name}»? Это нельзя отменить.", getString(R.string.delete)) {
                    app.keys.delete(key.id)
                    app.hosts.clearKey(key.id)
                    refresh()
                }
            }
            true
        }
        popup.show()
    }

    private fun rename(key: SshKeyEntry) {
        val box = dialogContainer()
        val edit = formEdit(value = key.name)
        box.addView(edit)
        AlertDialog.Builder(this)
            .setTitle(R.string.key_name)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                app.keys.update(key.copy(name = edit.text.toString().trim().ifEmpty { key.name }))
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this).setMessage(message).setPositiveButton(R.string.ok, null).show()
    }

    private inner class Adapter : BaseAdapter() {
        var items: List<SshKeyEntry> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_host, parent, false)
            val key = items[position]
            v.findViewById<ImageView>(R.id.icon).setImageResource(R.drawable.ic_key)
            v.findViewById<TextView>(R.id.title).text = key.name
            v.findViewById<TextView>(R.id.subtitle).text = "${key.type}  ${key.fingerprint.removePrefix("SHA256:").take(16)}…"
            v.findViewById<ImageButton>(R.id.more).setOnClickListener { showKeyMenu(key, it) }
            return v
        }
    }

    companion object {
        private const val REQUEST_FILE = 10
    }
}
