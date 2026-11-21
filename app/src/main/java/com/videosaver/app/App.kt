package com.videosaver.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DownloadRepository.init(this)
    }
}
