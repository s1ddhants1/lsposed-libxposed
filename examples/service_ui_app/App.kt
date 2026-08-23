package com.example.module.ui

import android.app.Application
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class App : Application() {

    companion object {
        const val TAG = "ModuleApp"
        var xposedService: XposedService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()

        // Register listener for Xposed Framework Service
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                Log.i(TAG, "Connected to Xposed Framework: ${service.frameworkName} v${service.frameworkVersion} (API ${service.apiVersion})")
                xposedService = service
            }

            override fun onServiceDied(service: XposedService) {
                Log.w(TAG, "Xposed Framework Service died")
                if (xposedService == service) {
                    xposedService = null
                }
            }
        })
    }
}
