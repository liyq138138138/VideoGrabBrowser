package com.videograb.browser

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class BookmarkManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getAll(): List<Bookmark> {
        val json = prefs.getString("bookmarks_list", "[]") ?: "[]"
        val type = object : TypeToken<List<Bookmark>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(bookmark: Bookmark) {
        val list = getAll().toMutableList()
        // Avoid duplicates
        if (list.any { it.url == bookmark.url }) return
        list.add(0, bookmark)
        save(list)
    }

    fun remove(url: String) {
        val list = getAll().toMutableList()
        list.removeAll { it.url == url }
        save(list)
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

    private fun save(list: List<Bookmark>) {
        prefs.edit().putString("bookmarks_list", gson.toJson(list)).apply()
    }
}
