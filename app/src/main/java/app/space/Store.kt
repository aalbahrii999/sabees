package app.space

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Entry(val title: String, val url: String, val time: Long)

class Store(context: Context) {
    private val prefs = context.getSharedPreferences("space", Context.MODE_PRIVATE)

    fun history(): List<Entry> = read("history")

    fun bookmarks(): List<Entry> = read("bookmarks")

    fun addHistory(title: String, url: String) {
        if (!url.startsWith("http")) return
        val clean = title.ifBlank { Urls.host(url) }
        val list = read("history").toMutableList()
        if (list.firstOrNull()?.url == url) {
            list[0] = Entry(clean, url, System.currentTimeMillis())
        } else {
            list.add(0, Entry(clean, url, System.currentTimeMillis()))
        }
        write("history", list.take(200))
    }

    fun isBookmarked(url: String): Boolean = bookmarks().any { it.url == url }

    fun toggleBookmark(title: String, url: String): Boolean {
        val list = bookmarks().toMutableList()
        val index = list.indexOfFirst { it.url == url }
        val saved = if (index >= 0) {
            list.removeAt(index)
            false
        } else {
            list.add(0, Entry(title.ifBlank { Urls.host(url) }, url, System.currentTimeMillis()))
            true
        }
        write("bookmarks", list.take(200))
        return saved
    }

    fun removeBookmark(url: String) {
        write("bookmarks", bookmarks().filter { it.url != url })
    }

    fun removeHistory(url: String, time: Long) {
        write("history", history().filter { it.url != url || it.time != time })
    }

    fun clearHistory() {
        prefs.edit().remove("history").apply()
    }

    fun saveSession(urls: List<String>, homes: List<Boolean>, index: Int, desktop: Boolean) {
        val array = JSONArray()
        urls.forEachIndexed { i, url ->
            array.put(JSONObject().put("url", url).put("home", homes[i]))
        }
        prefs.edit()
            .putString("tabs", array.toString())
            .putInt("index", index)
            .putBoolean("desktop", desktop)
            .apply()
    }

    fun session(): Triple<List<Pair<String, Boolean>>, Int, Boolean> {
        val raw = prefs.getString("tabs", null) ?: return Triple(emptyList(), 0, false)
        return try {
            val array = JSONArray(raw)
            val list = buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val url = item.optString("url")
                    add(url to item.optBoolean("home", url.isBlank()))
                }
            }
            Triple(list, prefs.getInt("index", 0), prefs.getBoolean("desktop", false))
        } catch (_: Exception) {
            Triple(emptyList(), 0, false)
        }
    }

    private fun read(key: String): List<Entry> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val url = item.optString("url")
                    if (url.isNotBlank()) {
                        add(Entry(item.optString("title"), url, item.optLong("time")))
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun write(key: String, list: List<Entry>) {
        val array = JSONArray()
        list.forEach { entry ->
            array.put(
                JSONObject()
                    .put("title", entry.title)
                    .put("url", entry.url)
                    .put("time", entry.time),
            )
        }
        prefs.edit().putString(key, array.toString()).apply()
    }
}
