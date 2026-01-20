package com.example.serialstressstrain

import android.app.PendingIntent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SerialDeviceUi(val id: Int, val title: String, val subtitle: String)

data class SerialUiState(
    val devices: List<SerialDeviceUi> = emptyList(),
    val selectedDeviceId: Int? = null,
    val baudRate: String = "9600",
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val status: String = "Disconnected",
    val log: List<String> = emptyList(),
    val error: String? = null
)

class SerialViewModel(
    private val usbManager: UsbManager,
    private val permissionIntent: PendingIntent
) : ViewModel() {

    private val driversById = mutableMapOf<Int, UsbSerialDriver>()
    private val _uiState = MutableStateFlow(SerialUiState())
    val uiState: StateFlow<SerialUiState> = _uiState.asStateFlow()

    private var connection: UsbDeviceConnection? = null
    private var port: UsbSerialPort? = null
    private var readJob: Job? = null
    private var pendingDeviceId: Int? = null
    private var currentDeviceId: Int? = null

    fun refreshDevices() {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        driversById.clear()
        val deviceList = drivers.map { driver ->
            val device = driver.device
            driversById[device.deviceId] = driver
            SerialDeviceUi(
                id = device.deviceId,
                title = device.deviceName ?: "USB device ${device.deviceId}",
                subtitle = "VID:${device.vendorId} PID:${device.productId}"
            )
        }
        val previousSelection = _uiState.value.selectedDeviceId
        val nextSelection = when {
            previousSelection != null && deviceList.any { it.id == previousSelection } -> previousSelection
            else -> deviceList.firstOrNull()?.id
        }
        _uiState.update { state ->
            state.copy(
                devices = deviceList,
                selectedDeviceId = nextSelection,
                status = if (state.isConnected) state.status else "Disconnected"
            )
        }
    }

    fun selectDevice(deviceId: Int?) {
        _uiState.update { it.copy(selectedDeviceId = deviceId, error = null) }
    }

    fun setBaudRate(value: String) {
        _uiState.update { it.copy(baudRate = value.filter { char -> char.isDigit() }, error = null) }
    }

    fun requestConnect() {
        val selectedId = _uiState.value.selectedDeviceId
        val baudRate = _uiState.value.baudRate.toIntOrNull()
        if (selectedId == null) {
            pushError("Pick a device before connecting.")
            return
        }
        if (baudRate == null) {
            pushError("Baud rate must be a number.")
            return
        }
        val driver = driversById[selectedId] ?: run {
            pushError("Selected device is no longer available.")
            refreshDevices()
            return
        }
        if (!usbManager.hasPermission(driver.device)) {
            pendingDeviceId = selectedId
            usbManager.requestPermission(driver.device, permissionIntent)
            _uiState.update { it.copy(isConnecting = true, status = "Requesting USB permission...") }
            return
        }
        openPort(driver, baudRate)
    }

    fun onPermissionResult(device: UsbDevice?, granted: Boolean) {
        val expectedId = pendingDeviceId ?: return
        val deviceId = device?.deviceId
        val resolvedDriver = when {
            deviceId == expectedId -> driversById[expectedId]
            deviceId == null -> {
                // Some devices/ROMs deliver permission callbacks without the device extra.
                refreshDevices()
                driversById[expectedId]
            }
            else -> null
        } ?: run {
            pendingDeviceId = null
            _uiState.update { it.copy(isConnecting = false, status = "Disconnected") }
            pushError("Selected device is no longer available.")
            return
        }
        if (deviceId != null && deviceId != expectedId) return
        if (!granted && !usbManager.hasPermission(resolvedDriver.device)) {
            pendingDeviceId = null
            pushError("USB permission denied.")
            _uiState.update { it.copy(isConnecting = false, status = "Permission denied") }
            return
        }
        val baudRate = _uiState.value.baudRate.toIntOrNull() ?: run {
            pendingDeviceId = null
            _uiState.update { it.copy(isConnecting = false, status = "Disconnected") }
            pushError("Baud rate must be a number.")
            return
        }
        pendingDeviceId = null
        openPort(resolvedDriver, baudRate)
    }

    fun onDeviceDetached(device: UsbDevice?) {
        if (device != null && device.deviceId == currentDeviceId) {
            appendLog("Device detached.")
            disconnect()
        }
        refreshDevices()
    }

    fun disconnect() {
        closePort()
        _uiState.update { it.copy(isConnected = false, isConnecting = false, status = "Disconnected") }
    }

    private fun openPort(driver: UsbSerialDriver, baudRate: Int) {
        closePort()
        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            pushError("Failed to open device. Check permission.")
            _uiState.update { it.copy(isConnecting = false, isConnected = false) }
            return
        }
        val port = driver.ports.firstOrNull()
        if (port == null) {
            connection.close()
            pushError("No serial ports available on this device.")
            _uiState.update { it.copy(isConnecting = false, isConnected = false) }
            return
        }
        try {
            port.open(connection)
            port.setParameters(
                baudRate,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            port.dtr = true
            port.rts = true
            this.connection = connection
            this.port = port
            currentDeviceId = driver.device.deviceId
            _uiState.update {
                it.copy(
                    isConnected = true,
                    isConnecting = false,
                    status = "Connected at $baudRate baud",
                    error = null
                )
            }
            startReadLoop()
        } catch (e: Exception) {
            connection.close()
            pushError("Failed to open port: ${e.message}")
            _uiState.update { it.copy(isConnecting = false, isConnected = false) }
        }
    }

    private fun startReadLoop() {
        readJob?.cancel()
        val activePort = port ?: return
        readJob = viewModelScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(1024)
            while (isActive && port === activePort) {
                try {
                    val len = activePort.read(buffer, 1000)
                    if (len > 0) {
                        val text = buffer.copyOf(len).toString(Charsets.UTF_8)
                        appendLog(text)
                    }
                } catch (ioe: IOException) {
                    appendLog("Read error: ${ioe.message}")
                    withContext(Dispatchers.Main) {
                        disconnect()
                    }
                    break
                } catch (e: Exception) {
                    appendLog("Unexpected error: ${e.message}")
                }
            }
        }
    }

    private fun appendLog(message: String) {
        _uiState.update { state ->
            val updated = (state.log + message).takeLast(200)
            state.copy(log = updated)
        }
    }

    private fun pushError(message: String) {
        _uiState.update { it.copy(error = message) }
        appendLog(message)
    }

    private fun closePort() {
        readJob?.cancel()
        readJob = null
        try {
            port?.close()
        } catch (_: Exception) {
        }
        try {
            connection?.close()
        } catch (_: Exception) {
        }
        port = null
        connection = null
        currentDeviceId = null
    }

    override fun onCleared() {
        super.onCleared()
        closePort()
    }

    companion object {
        fun factory(
            usbManager: UsbManager,
            permissionIntent: PendingIntent
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                @Suppress("UNCHECKED_CAST")
                return SerialViewModel(usbManager, permissionIntent) as T
            }
        }
    }
}
