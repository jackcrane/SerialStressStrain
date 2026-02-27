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
import kotlin.math.abs
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

data class ChartPoint(val x: Float, val y: Float)

data class SerialUiState(
    val devices: List<SerialDeviceUi> = emptyList(),
    val selectedDeviceId: Int? = null,
    val baudRate: String = "9600",
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val status: String = "Disconnected",
    val error: String? = null,
    val chartPoints: List<ChartPoint> = emptyList(),
    val sampleWindow: String = "10000",
    val showSettings: Boolean = true,
    val yMin: String = "",
    val yMax: String = "",
    val jogDistanceMm: Int = 10
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
    private var partialLine: String = ""
    private var latestMotorDistance: Float? = null
    private var xZeroOffset: Float = 0f

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

    fun setSampleWindow(value: String) {
        _uiState.update { it.copy(sampleWindow = value.filter { it.isDigit() }) }
    }

    fun setShowSettings(show: Boolean) {
        _uiState.update { it.copy(showSettings = show) }
    }

    fun setYMin(value: String) {
        _uiState.update { it.copy(yMin = value) }
    }

    fun setYMax(value: String) {
        _uiState.update { it.copy(yMax = value) }
    }

    fun setJogDistanceMm(value: Int) {
        if (value !in JOG_DISTANCE_OPTIONS_MM) return
        _uiState.update { it.copy(jogDistanceMm = value) }
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
            _uiState.update { it.copy(status = "Device detached") }
            disconnect()
        }
        refreshDevices()
    }

    fun disconnect() {
        closePort()
        _uiState.update {
            it.copy(
                isConnected = false,
                isConnecting = false,
                status = "Disconnected",
                showSettings = true
            )
        }
    }

    fun jogUp() {
        val distanceMm = _uiState.value.jogDistanceMm
        sendControlPacket(
            packet = movePacket(direction = MOVE_DIRECTION_UP, distanceMm = distanceMm),
            label = "Jog up ${distanceMm}mm"
        )
    }

    fun jogDown() {
        val distanceMm = _uiState.value.jogDistanceMm
        sendControlPacket(
            packet = movePacket(direction = MOVE_DIRECTION_DOWN, distanceMm = distanceMm),
            label = "Jog down ${distanceMm}mm"
        )
    }

    fun home() {
        sendControlPacket(
            packet = HOME_PACKET,
            label = "Home"
        )
    }

    fun clearPlot() {
        _uiState.update {
            it.copy(
                chartPoints = emptyList(),
                status = "Plot cleared",
                error = null
            )
        }
    }

    fun setChartZero() {
        val currentDistance = latestMotorDistance
        if (currentDistance == null) {
            pushError("No motor distance received yet.")
            return
        }
        xZeroOffset = currentDistance
        _uiState.update {
            it.copy(
                status = "Chart zero set",
                error = null
            )
        }
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
                    showSettings = false,
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
                        handleIncomingChunk(text)
                    }
                } catch (ioe: IOException) {
                    pushError("Read error: ${ioe.message}")
                    withContext(Dispatchers.Main) {
                        disconnect()
                    }
                    break
                } catch (e: Exception) {
                    pushError("Unexpected error: ${e.message}")
                }
            }
        }
    }

    private fun handleIncomingChunk(text: String) {
        val combined = (partialLine + text).replace("\r", "\n")
        val segments = combined.split("\n")
        if (combined.endsWith("\n")) {
            partialLine = ""
        } else {
            partialLine = segments.last()
        }
        val completeLines = if (partialLine.isEmpty()) segments else segments.dropLast(1)
        completeLines.forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isNotEmpty()) {
                processPacket(line)
            }
        }
    }

    private fun processPacket(line: String) {
        val parts = line.split(",")
        if (parts.size != 3) return
        val type = parts[0].toIntOrNull() ?: return
        val motorDistanceFromHome = parts[1].toFloatOrNull() ?: return
        val loadCellValue = parts[2].toFloatOrNull() ?: return
        when (type) {
            0 -> _uiState.update { state ->
                latestMotorDistance = motorDistanceFromHome
                val adjustedMotorDistance = motorDistanceFromHome - xZeroOffset
                val maxPoints = state.sampleWindow.toIntOrNull()?.coerceAtLeast(1) ?: 10_000
                val replacementIndex = state.chartPoints.indexOfFirst { existing ->
                    abs(existing.x - adjustedMotorDistance) <= X_MATCH_TOLERANCE
                }
                val updatedPoints = if (replacementIndex >= 0) {
                    state.chartPoints.toMutableList().apply {
                        this[replacementIndex] = ChartPoint(adjustedMotorDistance, loadCellValue)
                    }
                } else {
                    state.chartPoints + ChartPoint(adjustedMotorDistance, loadCellValue)
                }
                val trimmed = updatedPoints.takeLast(maxPoints)
                state.copy(chartPoints = trimmed)
            }
            1 -> _uiState.update { it.copy(chartPoints = emptyList()) }
        }
    }

    private fun sendControlPacket(packet: String, label: String) {
        val activePort = port
        if (activePort == null || !_uiState.value.isConnected) {
            pushError("Connect to a device before jogging.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                activePort.write(packet.toByteArray(Charsets.UTF_8), WRITE_TIMEOUT_MS)
                _uiState.update {
                    it.copy(
                        status = "$label command sent",
                        error = null
                    )
                }
            } catch (ioe: IOException) {
                pushError("Write error: ${ioe.message}")
                withContext(Dispatchers.Main) {
                    disconnect()
                }
            } catch (e: Exception) {
                pushError("Failed to send $label command: ${e.message}")
            }
        }
    }

    private fun movePacket(direction: Int, distanceMm: Int): String {
        return "$MOVE_TOOL,$direction,$distanceMm\n"
    }

    private fun pushError(message: String) {
        _uiState.update { it.copy(error = message) }
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
        partialLine = ""
        latestMotorDistance = null
        xZeroOffset = 0f
    }

    override fun onCleared() {
        super.onCleared()
        closePort()
    }

    companion object {
        private const val WRITE_TIMEOUT_MS = 250
        private const val X_MATCH_TOLERANCE = 0.0001f
        // Format: "<tool>,<direction>,<distance_mm>" for tool 0 (move):
        // direction is 1 for up, -1 for down, and distance is selected in the UI.
        private const val MOVE_TOOL = 0
        private const val MOVE_DIRECTION_UP = 1
        private const val MOVE_DIRECTION_DOWN = -1
        private val JOG_DISTANCE_OPTIONS_MM = listOf(1, 5, 10, 50)
        private const val HOME_PACKET = "1,0,0\n"

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
