package io.github.mortenfyhn.feltbok

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color.TRANSPARENT
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels { viewModelFactory { initializer { MainViewModel(application) } } }

    private val askLocation =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) vm.startLocationUpdates()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // From targetSdk 35 the system draws the app behind the status and navigation bars and
        // there's no opting out, so declare it rather than inherit it half-configured: transparent
        // bars. The status bar sits on the app's green band (StatusBarBand), so `dark` (= white
        // icons); the navigation bar on the light screens, so `light` (= dark icons), since
        // AppTheme is light-only. The screens pad themselves clear of both.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(TRANSPARENT, TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        configureOsmdroid(applicationContext)   // one-time map init, off the first map's path
        setContent { App(vm) }
        if (!hasLocation()) askLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    override fun onStart() { super.onStart(); if (hasLocation()) vm.startLocationUpdates() }
    override fun onStop() { super.onStop(); vm.stopLocationUpdates() }

    private fun hasLocation() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
}
