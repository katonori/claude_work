package com.katonori.gitmobile

import android.app.Application
import com.katonori.gitmobile.di.AppContainer

class GitMobileApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
