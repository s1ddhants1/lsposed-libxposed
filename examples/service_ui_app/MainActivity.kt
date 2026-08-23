package com.example.module.ui

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService

class MainActivity : AppCompatActivity() {

    private val service: XposedService?
        get() = App.xposedService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Assume layout contains switchFeature, btnRequestScope, btnHotReload

        val switchFeature = findViewById<Switch>(android.R.id.switch_widget)
        val btnRequestScope = findViewById<Button>(android.R.id.button1)
        val btnHotReload = findViewById<Button>(android.R.id.button2)

        // 1. Read / Write Remote Preferences
        service?.let { svc ->
            val prefs = svc.getRemotePreferences("main_settings")
            switchFeature?.isChecked = prefs.getBoolean("enable_feature", false)

            switchFeature?.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("enable_feature", isChecked).apply()
                Toast.makeText(this, "Preference saved to LSPosed daemon", Toast.LENGTH_SHORT).show()
            }
        }

        // 2. Dynamically Request Scope
        btnRequestScope?.setOnClickListener {
            val svc = service ?: run {
                Toast.makeText(this, "Xposed framework not active", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val targetApps = listOf("com.target.app", "com.another.app")
            svc.requestScope(targetApps, object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(approved: List<String>) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Scope approved: $approved", Toast.LENGTH_LONG).show()
                    }
                }

                override fun onScopeRequestFailed(message: String) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Scope request rejected: $message", Toast.LENGTH_LONG).show()
                    }
                }
            })
        }

        // 3. Query Hooked Targets & Trigger Live Hot Reload (API 102+)
        btnHotReload?.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (svc.apiVersion < XposedService.API_102) {
                Toast.makeText(this, "Hot reload requires API 102+", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val runningTargets: List<HookedTarget> = svc.runningTargets
            for (target in runningTargets) {
                Log.i("MainActivity", "Reloading target: ${target.processName} (pid=${target.pid})")

                val data = Bundle().apply {
                    putString("trigger_source", "MainActivity_UI")
                }

                svc.hotReloadModule(target, data) { targetProcess, result ->
                    runOnUiThread {
                        when (result.status) {
                            HotReloadResult.Status.SUCCESS ->
                                Toast.makeText(this@MainActivity, "Hot reloaded: ${targetProcess.processName}", Toast.LENGTH_SHORT).show()
                            HotReloadResult.Status.FAILED ->
                                Toast.makeText(this@MainActivity, "Reload failed: ${result.message}", Toast.LENGTH_LONG).show()
                            HotReloadResult.Status.UNSUPPORTED ->
                                Toast.makeText(this@MainActivity, "Reload unsupported on target", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
}
