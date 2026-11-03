package com.one4zero.sshterm.data

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import android.util.Log
import com.one4zero.sshterm.ssh.HostKeyStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Small JSON-array-in-a-file persistence helper. */
private class JsonFile(dir: File, name: String) {
    private val file = AtomicFile(File(dir, name))

    fun read(): JSONArray = try {
        JSONArray(String(file.readFully(), Charsets.UTF_8))
    } catch (e: java.io.FileNotFoundException) {
        JSONArray()
    } catch (e: Exception) {
        Log.w("JsonFile", "Corrupted file ${file.baseFile}", e)
        JSONArray()
    }

    fun write(array: JSONArray) {
        val out = file.startWrite()
        try {
            out.write(array.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    fun exists() = file.baseFile.exists()
}

private inline fun <T> JSONArray.mapObjects(f: (JSONObject) -> T?): List<T> {
    val list = ArrayList<T>(length())
    for (i in 0 until length()) {
        val o = optJSONObject(i) ?: continue
        runCatching { f(o) }.getOrNull()?.let { list.add(it) }
    }
    return list
}

/** Base for simple observable lists stored in a JSON file. Accessed from the main thread. */
abstract class ListStore<T>(context: Context, fileName: String) {
    private val json = JsonFile(context.filesDir, fileName)
    private val listeners = mutableListOf<() -> Unit>()
    protected var items: MutableList<T> = json.read().mapObjects { parse(it) }.toMutableList()

    protected val isNew = !json.exists()

    protected abstract fun parse(o: JSONObject): T
    protected abstract fun serialize(item: T): JSONObject

    fun all(): List<T> = items.toList()

    protected fun save() {
        val arr = JSONArray()
        items.forEach { arr.put(serialize(it)) }
        json.write(arr)
        listeners.toList().forEach { it() }
    }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }
}

class HostStore(context: Context) : ListStore<HostConfig>(context, "hosts.json") {
    override fun parse(o: JSONObject) = HostConfig.fromJson(o)
    override fun serialize(item: HostConfig) = item.toJson()

    fun get(id: String): HostConfig? = items.firstOrNull { it.id == id }

    fun sorted(): List<HostConfig> = items.sortedWith(
        compareByDescending<HostConfig> { it.lastUsed }.thenBy { it.displayName.lowercase() },
    )

    fun upsert(host: HostConfig) {
        if (host.transient) return
        val idx = items.indexOfFirst { it.id == host.id }
        if (idx >= 0) items[idx] = host else items.add(host)
        save()
    }

    fun delete(id: String) {
        items.removeAll { it.id == id }
        save()
    }

    fun touch(id: String) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            items[idx] = items[idx].copy(lastUsed = System.currentTimeMillis())
            save()
        }
    }

    fun clearKey(keyId: String) {
        var changed = false
        items = items.map {
            if (it.keyId == keyId) {
                changed = true
                it.copy(keyId = null)
            } else it
        }.toMutableList()
        if (changed) save()
    }
}

class KeyStoreRepo(context: Context) : ListStore<SshKeyEntry>(context, "keys.json") {
    override fun parse(o: JSONObject) = SshKeyEntry.fromJson(o)
    override fun serialize(item: SshKeyEntry) = item.toJson()

    fun get(id: String?): SshKeyEntry? = items.firstOrNull { it.id == id }

    fun add(key: SshKeyEntry) {
        items.add(key)
        save()
    }

    fun update(key: SshKeyEntry) {
        val idx = items.indexOfFirst { it.id == key.id }
        if (idx >= 0) {
            items[idx] = key
            save()
        }
    }

    fun delete(id: String) {
        items.removeAll { it.id == id }
        save()
    }
}

class SnippetStore(context: Context) : ListStore<Snippet>(context, "snippets.json") {
    init {
        if (isNew) {
            items = mutableListOf(
                Snippet(name = "", command = "ls -la", autoRun = true),
                Snippet(name = "", command = "cd ..", autoRun = true),
                Snippet(name = "", command = "df -h", autoRun = true),
                Snippet(name = "", command = "free -h", autoRun = true),
                Snippet(name = "", command = "top", autoRun = true),
                Snippet(name = "", command = "sudo ", autoRun = false),
                Snippet(name = "", command = "systemctl status ", autoRun = false),
                Snippet(name = "", command = "tail -f ", autoRun = false),
            )
            save()
        }
    }

    override fun parse(o: JSONObject) = Snippet.fromJson(o)
    override fun serialize(item: Snippet) = item.toJson()

    fun upsert(s: Snippet) {
        val idx = items.indexOfFirst { it.id == s.id }
        if (idx >= 0) items[idx] = s else items.add(s)
        save()
    }

    fun delete(id: String) {
        items.removeAll { it.id == id }
        save()
    }

    fun move(id: String, delta: Int) {
        val idx = items.indexOfFirst { it.id == id }
        val target = idx + delta
        if (idx < 0 || target !in items.indices) return
        val item = items.removeAt(idx)
        items.add(target, item)
        save()
    }
}

/** Trusted server host keys. Thread safe (used from connection threads). */
class KnownHostsStore(context: Context) : HostKeyStore {
    private val json = JsonFile(context.filesDir, "known_hosts.json")
    private val entries: MutableList<KnownHostKey> = json.read().mapObjects { KnownHostKey.fromJson(it) }.toMutableList()

    @Synchronized
    override fun find(host: String): List<KnownHostKey> = entries.filter { it.host == host }

    @Synchronized
    fun all(): List<KnownHostKey> = entries.toList()

    @Synchronized
    override fun put(entry: KnownHostKey) {
        entries.removeAll { it.host == entry.host && it.type == entry.type }
        entries.add(entry)
        persist()
    }

    @Synchronized
    override fun remove(host: String, type: String?) {
        entries.removeAll { it.host == host && (type == null || it.type == type) }
        persist()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        persist()
    }

    private fun persist() {
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        json.write(arr)
    }
}

/** Command history per host. */
class HistoryStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("history", Context.MODE_PRIVATE)

    fun get(hostId: String): List<String> {
        val raw = prefs.getString(hostId, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    fun add(hostId: String, command: String) {
        val cmd = command.trimEnd()
        if (cmd.isBlank()) return
        val list = get(hostId).toMutableList()
        list.remove(cmd)
        list.add(cmd)
        while (list.size > MAX) list.removeAt(0)
        prefs.edit().putString(hostId, JSONArray(list).toString()).apply()
    }

    fun remove(hostId: String, command: String) {
        val list = get(hostId).toMutableList()
        if (list.remove(command)) prefs.edit().putString(hostId, JSONArray(list).toString()).apply()
    }

    fun clear(hostId: String) {
        prefs.edit().remove(hostId).apply()
    }

    companion object {
        const val MAX = 300
    }
}

class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var fontSizeSp: Float
        get() = prefs.getFloat("fontSize", 13f)
        set(v) = prefs.edit().putFloat("fontSize", v.coerceIn(MIN_FONT, MAX_FONT)).apply()

    var scrollbackLines: Int
        get() = prefs.getInt("scrollback", 5000)
        set(v) = prefs.edit().putInt("scrollback", v).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keepScreenOn", true)
        set(v) = prefs.edit().putBoolean("keepScreenOn", v).apply()

    var noSuggestions: Boolean
        get() = prefs.getBoolean("noSuggestions", true)
        set(v) = prefs.edit().putBoolean("noSuggestions", v).apply()

    var vibrateOnKeys: Boolean
        get() = prefs.getBoolean("vibrate", true)
        set(v) = prefs.edit().putBoolean("vibrate", v).apply()

    var showSnippets: Boolean
        get() = prefs.getBoolean("showSnippets", true)
        set(v) = prefs.edit().putBoolean("showSnippets", v).apply()

    var showExtraKeys: Boolean
        get() = prefs.getBoolean("showExtraKeys", true)
        set(v) = prefs.edit().putBoolean("showExtraKeys", v).apply()

    var smartPaste: Boolean
        get() = prefs.getBoolean("smartPaste", true)
        set(v) = prefs.edit().putBoolean("smartPaste", v).apply()

    var directInputDefault: Boolean
        get() = prefs.getBoolean("directInput", false)
        set(v) = prefs.edit().putBoolean("directInput", v).apply()

    var colorScheme: Int
        get() = prefs.getInt("colorScheme", 0)
        set(v) = prefs.edit().putInt("colorScheme", v).apply()

    var lastQuickConnect: String
        get() = prefs.getString("lastQuick", "") ?: ""
        set(v) = prefs.edit().putString("lastQuick", v).apply()

    var notificationAsked: Boolean
        get() = prefs.getBoolean("notifAsked", false)
        set(v) = prefs.edit().putBoolean("notifAsked", v).apply()

    var backgroundHintShown: Boolean
        get() = prefs.getBoolean("bgHint", false)
        set(v) = prefs.edit().putBoolean("bgHint", v).apply()

    companion object {
        const val MIN_FONT = 6f
        const val MAX_FONT = 40f
    }
}
