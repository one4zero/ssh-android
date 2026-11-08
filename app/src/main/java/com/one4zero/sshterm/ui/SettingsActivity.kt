package com.one4zero.sshterm.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toolbar
import com.one4zero.sshterm.App
import com.one4zero.sshterm.R
import com.one4zero.sshterm.terminal.TerminalColors

class SettingsActivity : Activity() {
    private val settings get() = App.instance.settings
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scroll)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setActionBar(toolbar)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.settings)
        setupEdgeToEdge(findViewById(R.id.root), toolbar)
        content = findViewById(R.id.content)
        build()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun build() {
        content.removeAllViews()
        header("Терминал")
        valueRow(getString(R.string.settings_font), "${fmt(settings.fontSizeSp)} sp") { pickFont() }
        valueRow(getString(R.string.settings_scheme), TerminalColors.get(settings.colorScheme).name) { pickScheme() }
        valueRow(getString(R.string.settings_scrollback), settings.scrollbackLines.toString()) { pickScrollback() }
        switchRow(getString(R.string.settings_keep_screen), settings.keepScreenOn) { settings.keepScreenOn = it }

        header("Ввод")
        switchRow(getString(R.string.settings_direct_default), settings.directInputDefault) { settings.directInputDefault = it }
        switchRow(getString(R.string.settings_no_suggestions), settings.noSuggestions) { settings.noSuggestions = it }
        switchRow(getString(R.string.settings_smart_paste), settings.smartPaste) { settings.smartPaste = it }
        switchRow(getString(R.string.settings_extra_keys), settings.showExtraKeys) { settings.showExtraKeys = it }
        switchRow(getString(R.string.settings_snippets), settings.showSnippets) { settings.showSnippets = it }
        switchRow(getString(R.string.settings_vibrate), settings.vibrateOnKeys) { settings.vibrateOnKeys = it }

        header("Безопасность")
        valueRow(getString(R.string.known_hosts_clear), "Сохранено: ${App.instance.knownHosts.all().size}") {
            confirm(null, "Удалить все сохранённые ключи серверов? При следующем подключении нужно будет подтвердить ключ заново.", getString(R.string.delete)) {
                App.instance.knownHosts.clear()
                build()
            }
        }

        header(getString(R.string.about))
        valueRow(getString(R.string.about), "${getString(R.string.app_name)} ${appVersion()}") {
            startActivity(android.content.Intent(this, AboutActivity::class.java))
        }
    }

    private fun appVersion(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""

    private fun fmt(v: Float) = if (v % 1f == 0f) v.toInt().toString() else v.toString()

    private fun header(text: String) {
        content.addView(TextView(this, null, 0, R.style.SectionHeader).apply {
            this.text = text
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
    }

    private fun rowContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(dp(20), dp(8), dp(16), dp(8))
        val tv = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        setBackgroundResource(tv.resourceId)
        isClickable = true
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun switchRow(text: String, value: Boolean, onChange: (Boolean) -> Unit) {
        val row = rowContainer()
        row.addView(label(text))
        val sw = Switch(this).apply {
            isChecked = value
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        content.addView(row)
    }

    private fun valueRow(text: String, value: String, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(56)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(16), dp(10))
            val tv = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            isClickable = true
        }
        row.addView(TextView(this).apply {
            this.text = text
            textSize = 16f
        })
        row.addView(TextView(this).apply {
            this.text = value
            textSize = 14f
            setTextColor(getColor(R.color.accent))
        })
        row.setOnClickListener { onClick() }
        content.addView(row)
    }

    private fun pickFont() {
        val sizes = listOf(8f, 9f, 10f, 11f, 12f, 13f, 14f, 15f, 16f, 18f, 20f, 22f, 24f, 28f)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_font)
            .setSingleChoiceItems(sizes.map { "${fmt(it)} sp" }.toTypedArray(), sizes.indexOf(settings.fontSizeSp)) { d, which ->
                settings.fontSizeSp = sizes[which]
                d.dismiss()
                build()
            }
            .show()
    }

    private fun pickScheme() {
        val names = TerminalColors.SCHEMES.map { it.name }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_scheme)
            .setSingleChoiceItems(names.toTypedArray(), settings.colorScheme) { d, which ->
                settings.colorScheme = which
                d.dismiss()
                build()
            }
            .show()
    }

    private fun pickScrollback() {
        val values = listOf(1000, 2000, 5000, 10000, 20000)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_scrollback)
            .setSingleChoiceItems(values.map { it.toString() }.toTypedArray(), values.indexOf(settings.scrollbackLines)) { d, which ->
                settings.scrollbackLines = values[which]
                d.dismiss()
                build()
            }
            .show()
    }
}
