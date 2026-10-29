package com.noi.karaokehost

data class Song(
    val videoId: String,
    val title: String,
    val thumbnail: String,
    val duration: Int,
    val addedBy: String = "host"
)

fun fmtDuration(sec: Int): String = if (sec <= 0) "" else "%d:%02d".format(sec / 60, sec % 60)
