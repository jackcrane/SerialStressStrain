package com.example.serialstressstrain

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.example.serialstressstrain.ui.SerialScreen
import com.example.serialstressstrain.usb.SerialUsbReceiver
import com.example.serialstressstrain.ui.theme.SerialStressStrainTheme

class MainActivity : ComponentActivity() {

    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val usbPermissionAction = "${BuildConfig.APPLICATION_ID}.USB_PERMISSION"

    private val permissionIntent: PendingIntent by lazy {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // UsbManager fills extras on our PendingIntent; must be mutable on API 31+
                PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_IMMUTABLE
            }
        PendingIntent.getBroadcast(
            this,
            0,
            Intent(usbPermissionAction),
            flags
        )
    }

    private val serialViewModel: SerialViewModel by viewModels {
        SerialViewModel.factory(usbManager, permissionIntent)
    }

    private val usbReceiver by lazy {
        SerialUsbReceiver(
            permissionAction = usbPermissionAction,
            onPermission = { device, granted -> serialViewModel.onPermissionResult(device, granted) },
            onAttached = { serialViewModel.refreshDevices() },
            onDetached = { device -> serialViewModel.onDeviceDetached(device) }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val usbFilter = IntentFilter().apply {
            addAction(usbPermissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, usbFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, usbFilter)
        }

        setContent {
            val state by serialViewModel.uiState.collectAsState()

            LaunchedEffect(Unit) {
                serialViewModel.refreshDevices()
            }

            SerialStressStrainTheme {
                SerialScreen(
                    state = state,
                    onRefresh = { serialViewModel.refreshDevices() },
                    onDeviceSelected = { serialViewModel.selectDevice(it) },
                    onBaudChange = { serialViewModel.setBaudRate(it) },
                    onConnect = { serialViewModel.requestConnect() },
                    onDisconnect = { serialViewModel.disconnect() }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }
}
