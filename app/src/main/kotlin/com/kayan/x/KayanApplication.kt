package com.kayan.x

import android.app.Application
import timber.log.Timber

class KayanApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
