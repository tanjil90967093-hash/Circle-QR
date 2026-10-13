package com.example

import android.app.Application
import com.example.data.repository.AppRepository

class UtilityHubApp : Application() {
    lateinit var repository: AppRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository = AppRepository(this)
    }

    companion object {
        lateinit var instance: UtilityHubApp
            private set
    }
}
