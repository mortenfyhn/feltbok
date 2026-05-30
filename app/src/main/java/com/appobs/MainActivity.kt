package com.appobs

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.util.Locale
import kotlinx.coroutines.delay

private val GREEN = Color(0xFF2E7D32)
private val AMBER = Color(0xFFF57F17)
private val RED = Color(0xFFC62828)

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen(viewModel, onShare = ::shareRecordings)
            }
        }
    }

    // Keep GPS warm only while the screen is visible (see LocationTracker).
    override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) viewModel.startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopLocationUpdates()
    }

    fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun shareRecordings() {
        val files = viewModel.recordings()
        if (files.isEmpty()) return
        val uris = ArrayList(files.map {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", it)
        })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "audio/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Del opptak"))
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel, onShare: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val count by viewModel.count.collectAsState()
    val lastSaved by viewModel.lastSaved.collectAsState()
    val fix by viewModel.fix.collectAsState()
    var ready by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        ready = grants[Manifest.permission.RECORD_AUDIO] == true
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) viewModel.startLocationUpdates()
    }

    LaunchedEffect(Unit) {
        launcher.launch(
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
        )
    }

    // Tick once a second so the GPS-age readout stays live even when no new fix arrives.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { delay(1000); tick++ }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Fixed-height slots keep the record button from shifting as the GPS
            // lines and the status message appear and change.
            Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.TopCenter) {
                GpsStatus(fix, tick)
            }

            Spacer(Modifier.weight(1f))

            RecordButton(
                recording = state == AppState.RECORDING,
                enabled = ready,
                onClick = {
                    if (state == AppState.RECORDING) viewModel.stopRecording()
                    else viewModel.startRecording()
                }
            )

            Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) {
                lastSaved?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }

            Spacer(Modifier.weight(1f))

            Text("$count opptak", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { viewModel.deleteLast() }, enabled = count > 0) {
                    Text("Angre siste")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onShare, enabled = count > 0) { Text("Del opptak") }
            }
        }
    }
}

@Composable
private fun RecordButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    // Classic record button: a ring with an inner shape that morphs from a
    // circle (idle) to a rounded square (recording) and turns red — no words.
    val innerSize by animateDpAsState(if (recording) 40.dp else 80.dp, label = "size")
    val innerCorner by animateDpAsState(if (recording) 10.dp else 40.dp, label = "corner")
    val innerColor = if (recording) RED else MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(112.dp)
            .clip(CircleShape)
            .border(4.dp, Color(0xFFBDBDBD), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(innerSize)
                .clip(RoundedCornerShape(innerCorner))
                .background(innerColor)
        )
    }
}

@Composable
private fun GpsStatus(fix: GpsFix?, @Suppress("UNUSED_PARAMETER") tick: Int) {
    val (text, color) = when {
        fix == null -> "GPS: ingen posisjon" to RED
        fix.accuracyM.isNaN() -> "GPS: nøyaktighet ukjent" to AMBER
        else -> {
            val acc = fix.accuracyM.toInt()
            val age = fix.ageSeconds()
            val label = "GPS ±${acc} m" + if (age > 3) " · ${formatAge(age)} gammel" else ""
            val c = when {
                age > 8 -> AMBER          // not updating — may need to resettle
                acc <= 15 -> GREEN
                acc <= 30 -> AMBER
                else -> RED               // too imprecise — wait for it to settle
            }
            label to c
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        fix?.let {
            Text(
                String.format(Locale.US, "%.5f, %.5f", it.lat, it.lon),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun formatAge(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60} min"
    else -> "${seconds / 3600} t ${(seconds % 3600) / 60} min"
}
