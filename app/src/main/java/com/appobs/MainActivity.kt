package com.appobs

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat

private val Moss = Color(0xFF5B7A2B)
private val MossDark = Color(0xFF41591D)

private val MossColors = lightColorScheme(
    primary = Moss,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8C6),
    onPrimaryContainer = MossDark,
    secondary = MossDark,
    onSecondary = Color.White,
    background = Color(0xFFEEF1F0),
    onBackground = Color(0xFF1C2624),
    surface = Color.White,
    onSurface = Color(0xFF1C2624),
    surfaceVariant = Color(0xFFF2F5F3),
    onSurfaceVariant = Color(0xFF6B7A76),
    outline = Color(0xFFCBD3D0),
    error = Color(0xFFB3261E),
)

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val askLocation =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) vm.startLocationUpdates()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
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

@Composable
fun App(vm: MainViewModel) {
    MaterialTheme(colorScheme = MossColors) {
        Surface(color = MaterialTheme.colorScheme.background) {
            when (vm.screen) {
                Screen.LIST -> ListScreen(vm)
                Screen.SEARCH -> SearchScreen(vm)
                Screen.DETAIL -> DetailScreen(vm)
                Screen.LOCALITY -> LocalityScreen(vm)
            }
            if (vm.showExport) ExportDialog(vm)
        }
    }
}
