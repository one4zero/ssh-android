package com.one4zero.sshterm.ui

import android.app.Activity
import android.app.AlertDialog
import android.widget.CheckBox
import com.one4zero.sshterm.App
import com.one4zero.sshterm.R
import com.one4zero.sshterm.data.Snippet

/** Add / edit dialog for a snippet (saved command). */
fun Activity.showSnippetDialog(existing: Snippet?, prefill: String = "", onDone: () -> Unit = {}) {
    val box = dialogContainer()
    box.addView(formLabel(getString(R.string.snippet_command)))
    val command = formEdit(value = existing?.command ?: prefill, multiLine = true)
    box.addView(command)
    box.addView(formLabel(getString(R.string.snippet_name)))
    val name = formEdit(value = existing?.name ?: "")
    box.addView(name)
    val autoRun = CheckBox(this).apply {
        text = getString(R.string.snippet_autorun)
        isChecked = existing?.autoRun ?: false
        setPadding(paddingLeft, dp(8), paddingRight, dp(8))
    }
    box.addView(autoRun)

    val builder = AlertDialog.Builder(this)
        .setTitle(if (existing == null) R.string.snippet_add else R.string.edit)
        .setView(box)
        .setPositiveButton(R.string.save, null)
        .setNegativeButton(R.string.cancel, null)
    if (existing != null) {
        builder.setNeutralButton(R.string.delete) { _, _ ->
            App.instance.snippets.delete(existing.id)
            onDone()
        }
    }
    val dialog = builder.create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val cmd = command.text.toString()
            if (cmd.isBlank()) {
                command.error = getString(R.string.snippet_command)
                return@setOnClickListener
            }
            val s = (existing ?: Snippet(name = "", command = "")).copy(
                name = name.text.toString().trim(),
                command = cmd.trimEnd('\n'),
                autoRun = autoRun.isChecked,
            )
            App.instance.snippets.upsert(s)
            dialog.dismiss()
            onDone()
        }
    }
    dialog.show()
}
