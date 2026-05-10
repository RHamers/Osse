package com.rhamers.osse.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rhamers.osse.player.MultiTrackService
import com.rhamers.osse.ui.theme.OsseTheme
import com.rhamers.osse.ui.tracks.TrackScreen

class MainActivity : ComponentActivity() {

    private var binder by mutableStateOf<MultiTrackService.LocalBinder?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service as MultiTrackService.LocalBinder
        }
        override fun onServiceDisconnected(name: ComponentName) {
            binder = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OsseTheme {
                TrackScreen(binder = binder)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, MultiTrackService::class.java)
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        unbindService(connection)
        binder = null
    }
}
