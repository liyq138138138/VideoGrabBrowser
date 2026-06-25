package com.videograb.browser

import android.content.Context
import android.content.SharedPreferences

class BookmarkManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("bookmarks_v2", Context.MODE_PRIVATE)

    fun getAll(): List<Bookmark> {
        val count = prefs.getInt("count", 0)
        val list = mutableListOf<Bookmark>()
        for (i in 0 until count) {
            val url = prefs.getString("url_$i", null) ?: continue
            val title = prefs.getString("title_$i", "") ?: ""
            val id = prefs.getLong("id_$i", 0L)
            list.add(Bookmark(id = id, title = title, url = url))
        }
        return list
    }

    fun add(bookmark: Bookmark) {
        val list = getAll().toMutableList()
        if (list.any { it.url == bookmark.url }) return
        list.add(0, bookmark)
        saveAll(list)
    }

    fun remove(url: String) {
        val list = getAll().toMutableList()
        list.removeAll { it.url == url }
        saveAll(list)
    }

    fun isBookmarked(url: String): Boolean {
        return getAll().any { it.url == url }
    }

    fun toggle(url: String, title: String) {
        if (isBookmarked(url)) {
            remove(url)
        } else {
            add(Bookmark(title = title, url = url))
        }
    }

    private fun saveAll(list: List<Bookmark>) {
        val editor = prefs.edit()
        editor.putInt("count", list.size)
        list.forEachIndexed { i, b ->
            editor.putLong("id_$i", b.id)
            editor.putString("title_$i", b.title)
            editor.putString("url_$i", b.url)
        }
        editor.apply()
    }
}
