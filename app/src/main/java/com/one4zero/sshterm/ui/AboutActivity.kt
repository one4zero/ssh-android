package com.one4zero.sshterm.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toolbar
import com.one4zero.sshterm.R

/** "About" screen: version, author, credits and usage tips. */
class AboutActivity : Activity() {
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scroll)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setActionBar(toolbar)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.about)
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
        val info = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val versionName = info?.versionName ?: ""
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info?.longVersionCode ?: 0L else info?.versionCode?.toLong() ?: 0L

        content.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(28)
            }
        })
        content.addView(centered(getString(R.string.app_name), 26f, bold = true).apply { setPadding(0, dp(10), 0, 0) })
        content.addView(
            centered(getString(R.string.about_version, versionName, versionCode), 14f).apply {
                setTextColor(currentTextColorSecondary())
                setPadding(0, dp(2), 0, 0)
            },
        )
        content.addView(
            centered(getString(R.string.about_description), 15f).apply {
                setPadding(dp(28), dp(14), dp(28), dp(4))
            },
        )

        section(getString(R.string.about_author_title))
        body(getString(R.string.about_author), 17f, bold = true)

        section(getString(R.string.about_built_title))
        body(getString(R.string.about_built_with))

        section(getString(R.string.about_source_title))
        link(REPO_URL)

        section(getString(R.string.about_changes_title))
        body(getString(R.string.about_changes))

        section(getString(R.string.about_tips_title))
        body(getString(R.string.about_tips))

        section(getString(R.string.about_libraries_title))
        body(getString(R.string.about_libraries))
    }

    private fun centered(text: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        gravity = Gravity.CENTER_HORIZONTAL
        if (bold) setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun section(title: String) {
        content.addView(TextView(this, null, 0, R.style.SectionHeader).apply {
            text = title
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(dp(20), dp(20), dp(20), dp(6))
        })
    }

    private fun body(text: String, size: Float = 14f, bold: Boolean = false) {
        content.addView(TextView(this).apply {
            this.text = text
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(0f, 1.15f)
            setPadding(dp(20), 0, dp(20), 0)
        })
    }

    private fun link(url: String) {
        content.addView(TextView(this).apply {
            text = url
            textSize = 14f
            setTextColor(getColor(R.color.accent))
            setPadding(dp(20), dp(4), dp(20), dp(4))
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    copyToClipboard(url)
                }
            }
        })
    }

    companion object {
        private const val REPO_URL = "https://github.com/one4zero/ssh-android"
    }
}
