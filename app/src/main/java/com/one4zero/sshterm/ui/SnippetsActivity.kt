package com.one4zero.sshterm.ui

import android.app.Activity
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
import com.one4zero.sshterm.data.Snippet

class SnippetsActivity : Activity() {
    private val app get() = App.instance
    private val adapter = Adapter()
    private lateinit var empty: TextView
    private val listener: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setActionBar(toolbar)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.snippets)
        setupEdgeToEdge(findViewById(R.id.root), toolbar)

        empty = findViewById(R.id.empty)
        empty.text = getString(R.string.snippet_none)
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> showSnippetDialog(adapter.getItem(position)) }
        list.setOnItemLongClickListener { _, view, position, _ ->
            showMenu(adapter.getItem(position), view)
            true
        }
        findViewById<ImageButton>(R.id.fab).setOnClickListener { showSnippetDialog(null) }
        app.snippets.addListener(listener)
        refresh()
    }

    override fun onDestroy() {
        app.snippets.removeListener(listener)
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun refresh() {
        adapter.items = app.snippets.all()
        adapter.notifyDataSetChanged()
        empty.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showMenu(snippet: Snippet, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, R.string.edit)
        popup.menu.add(0, 2, 1, R.string.move_up)
        popup.menu.add(0, 3, 2, R.string.move_down)
        popup.menu.add(0, 4, 3, R.string.delete)
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> showSnippetDialog(snippet)
                2 -> app.snippets.move(snippet.id, -1)
                3 -> app.snippets.move(snippet.id, 1)
                4 -> app.snippets.delete(snippet.id)
            }
            true
        }
        popup.show()
    }

    private inner class Adapter : BaseAdapter() {
        var items: List<Snippet> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_host, parent, false)
            val s = items[position]
            v.findViewById<ImageView>(R.id.icon).setImageResource(if (s.autoRun) R.drawable.ic_send else R.drawable.ic_edit)
            v.findViewById<TextView>(R.id.title).text = s.label
            v.findViewById<TextView>(R.id.subtitle).text =
                (if (s.autoRun) "▶ " else "✎ ") + s.command.replace('\n', '⏎')
            v.findViewById<ImageButton>(R.id.more).setOnClickListener { showMenu(s, it) }
            return v
        }
    }
}
