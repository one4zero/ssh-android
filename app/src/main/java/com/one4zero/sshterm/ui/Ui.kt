package com.one4zero.sshterm.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.PersistableBundle
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max

fun Context.dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun Context.toast(text: CharSequence) {
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}

/**
 * Draws the activity edge-to-edge and pads [root] so content is not hidden behind the
 * status bar, navigation bar, display cutout or the soft keyboard.
 */
fun Activity.setupEdgeToEdge(root: View, top: View) {
    if (Build.VERSION.SDK_INT >= 30) {
        window.setDecorFitsSystemWindows(false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        val topPadding = top.paddingTop
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            top.setPadding(top.paddingLeft, topPadding + bars.top, top.paddingRight, top.paddingBottom)
            v.setPadding(bars.left, 0, bars.right, max(bars.bottom, ime.bottom))
            WindowInsets.CONSUMED
        }
        root.requestApplyInsets()
    } else {
        root.fitsSystemWindows = true
    }
}

fun Context.copyToClipboard(text: String, label: String = "SSH Term", sensitive: Boolean = false) {
    val cm = getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText(label, text)
    if (sensitive && Build.VERSION.SDK_INT >= 24) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
        }
    }
    cm.setPrimaryClip(clip)
    // Android 13+ shows its own confirmation
    if (Build.VERSION.SDK_INT < 33) toast(getString(com.one4zero.sshterm.R.string.copied))
}

fun Context.clipboardText(): String? {
    val cm = getSystemService(ClipboardManager::class.java)
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(this)?.toString()
}

fun Context.confirm(title: String?, message: String, positive: String, onYes: () -> Unit) {
    AlertDialog.Builder(this)
        .apply { if (title != null) setTitle(title) }
        .setMessage(message)
        .setPositiveButton(positive) { _, _ -> onYes() }
        .setNegativeButton(com.one4zero.sshterm.R.string.cancel, null)
        .show()
}

/** Vertical container with padding for dialog forms. */
fun Context.dialogContainer(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val p = dp(22)
    setPadding(p, dp(8), p, 0)
}

fun Context.formLabel(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 13f
    setTextColor(currentTextColorSecondary())
    setPadding(0, dp(12), 0, 0)
}

fun Context.currentTextColorSecondary(): Int {
    val tv = TypedValue()
    theme.resolveAttribute(android.R.attr.textColorSecondary, tv, true)
    return if (tv.resourceId != 0) getColor(tv.resourceId) else tv.data
}

fun Context.formEdit(
    hint: String? = null,
    value: String = "",
    secret: Boolean = false,
    monospace: Boolean = false,
    multiLine: Boolean = false,
): EditText = EditText(this).apply {
    this.hint = hint
    setText(value)
    inputType = when {
        secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        multiLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        monospace -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }
    if (monospace || multiLine) typeface = android.graphics.Typeface.MONOSPACE
    if (multiLine) {
        minLines = 3
        maxLines = 8
        textSize = 13f
    } else {
        isSingleLine = true
    }
    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
