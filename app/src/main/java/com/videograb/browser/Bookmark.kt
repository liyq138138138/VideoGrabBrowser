package com.videograb.browser

data class Bookmark(
    val id: Long = System.currentTimeMillis(),
    val title: String,
    val url: String,
    val addedAt: Long = System.currentTimeMillis()
)
