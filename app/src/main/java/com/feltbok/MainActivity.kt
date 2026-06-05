package com.feltbok

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat

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
    AppTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            // System back navigates within the app instead of exiting (except on the list).
            BackHandler(enabled = vm.showExport || vm.screen != Screen.LIST) {
                when {
                    vm.showExport -> vm.closeExport()
                    vm.screen == Screen.LOCALITY -> vm.leaveLocalityPicker()
                    vm.screen == Screen.SYNC -> vm.closeSync()
                    vm.screen == Screen.SEARCH -> vm.cancelSearch()
                    vm.screen == Screen.DETAIL -> vm.cancel()
                    else -> {}
                }
            }
            when (vm.screen) {
                Screen.LIST -> ListScreen(vm)
                Screen.SEARCH -> SearchScreen(vm)
                Screen.DETAIL -> DetailScreen(vm)
                Screen.LOCALITY -> LocalityScreen(vm)
                Screen.SYNC -> SyncScreen(vm)
            }
            if (vm.showExport) ExportDialog(vm)
        }
    }
}
