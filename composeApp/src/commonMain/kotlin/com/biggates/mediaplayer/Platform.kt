package com.biggates.mediaplayer

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform