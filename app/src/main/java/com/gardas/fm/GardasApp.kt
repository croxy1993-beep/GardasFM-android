package com.gardas.fm

import android.app.Application

class GardasApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)      // önce depo, sonra çalma motoru
        Engine.init(this)
    }
}
