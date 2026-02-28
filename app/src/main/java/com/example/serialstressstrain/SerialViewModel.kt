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
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SerialDeviceUi(val id: Int, val title: String, val subtitle: String)

data class ChartPoint(val x: Float, val y: Float)

data class FailurePointAnnotation(
    val x: Float,
    val y: Float,
    val deformationMm: Float,
    val maxStrengthKg: Float
)

data class SerialUiState(
    val devices: List<SerialDeviceUi> = emptyList(),
    val selectedDeviceId: Int? = null,
    val baudRate: String = "115200",
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val status: String = "Disconnected",
    val error: String? = null,
    val chartPoints: List<ChartPoint> = emptyList(),
    val failurePoint: FailurePointAnnotation? = null,
    val latestMotorPositionMm: Float? = null,
    val latestLoadCellRaw: Float? = null,
    val sampleWindow: String = "10000",
    val showSettings: Boolean = true,
    val yMin: String = "",
    val yMax: String = "",
    val jogDistanceMm: Int = 10,
    val cycleStartLoadValue: String = "300",
    val cycleStopLoadValue: String = "15",
    val isCycleRunning: Boolean = false
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
    private var latestLoadCellReading: Float? = null
    private var latestMotorDistanceUpdateAtMs: Long = 0L
    private var xZeroOffset: Float = 0f
    private var cycleJob: Job? = null
    private val recentSamples = ArrayDeque<ChartPoint>()
    private var maxStrengthRawSinceReset: Float? = null

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

    fun setCycleStartLoadValue(value: String) {
        _uiState.update { it.copy(cycleStartLoadValue = sanitizeDecimalInput(value)) }
    }

    fun setCycleStopLoadValue(value: String) {
        _uiState.update { it.copy(cycleStopLoadValue = sanitizeDecimalInput(value)) }
    }

    fun toggleCycle() {
        if (_uiState.value.isCycleRunning) {
            stopCycle(updateStatus = true)
            return
        }
        startCycle()
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
            packet = movePacket(direction = MOVE_DIRECTION_UP, distanceMm = distanceMm.toFloat()),
            label = "Jog up ${distanceMm}mm"
        )
    }

    fun jogDown() {
        val distanceMm = _uiState.value.jogDistanceMm
        sendControlPacket(
            packet = movePacket(direction = MOVE_DIRECTION_DOWN, distanceMm = distanceMm.toFloat()),
            label = "Jog down ${distanceMm}mm"
        )
    }

    fun home() {
        sendControlPacket(
            packet = HOME_PACKET,
            label = "Home"
        )
    }

    fun mtft() {
        sendControlPacket(
            packet = MTFT_PACKET,
            label = "MTFT"
        )
    }

    fun clearPlot() {
        resetFailureTracking()
        _uiState.update {
            it.copy(
                chartPoints = emptyList(),
                failurePoint = null,
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
            0 -> {
                latestMotorDistance = motorDistanceFromHome
                latestLoadCellReading = loadCellValue
                latestMotorDistanceUpdateAtMs = System.currentTimeMillis()
                val adjustedMotorDistance = motorDistanceFromHome - xZeroOffset
                val incomingPoint = ChartPoint(adjustedMotorDistance, loadCellValue)
                val detectedFailurePoint = detectFailurePoint(
                    incomingPoint = incomingPoint,
                    existing = _uiState.value.failurePoint
                )
                _uiState.update { state ->
                    val maxPoints = state.sampleWindow.toIntOrNull()?.coerceAtLeast(1) ?: 10_000
                    val replacementIndex = state.chartPoints.indexOfFirst { existing ->
                        abs(existing.x - adjustedMotorDistance) <= X_MATCH_TOLERANCE
                    }
                    val updatedPoints = if (replacementIndex >= 0) {
                        state.chartPoints.toMutableList().apply {
                            this[replacementIndex] = incomingPoint
                        }
                    } else {
                        state.chartPoints + incomingPoint
                    }
                    val trimmed = updatedPoints.takeLast(maxPoints)
                    state.copy(
                        chartPoints = trimmed,
                        failurePoint = state.failurePoint ?: detectedFailurePoint,
                        latestMotorPositionMm = adjustedMotorDistance,
                        latestLoadCellRaw = loadCellValue
                    )
                }
            }
            1 -> {
                resetFailureTracking()
                _uiState.update { it.copy(chartPoints = emptyList(), failurePoint = null) }
            }
        }
    }

    private fun sendControlPacket(packet: String, label: String) {
        if (port == null || !_uiState.value.isConnected) {
            pushError("Connect to a device before jogging.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                writeControlPacket(packet = packet, label = label)
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

    private fun startCycle() {
        if (cycleJob != null || _uiState.value.isCycleRunning) return
        if (port == null || !_uiState.value.isConnected) {
            pushError("Connect to a device before starting cycle mode.")
            return
        }
        val startLoadValue = _uiState.value.cycleStartLoadValue.toFloatOrNull()
        val stopLoadValue = _uiState.value.cycleStopLoadValue.toFloatOrNull()
        if (startLoadValue == null || stopLoadValue == null) {
            pushError("Cycle start/stop values must be numbers.")
            return
        }
        if (startLoadValue <= stopLoadValue) {
            pushError("Cycle start value must be greater than cycle stop value.")
            return
        }

        _uiState.update {
            it.copy(
                isCycleRunning = true,
                status = "Cycle mode starting (start>$startLoadValue, stop<$stopLoadValue)...",
                error = null
            )
        }

        cycleJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    if (!runCyclePhase(
                            direction = MOVE_DIRECTION_DOWN,
                            threshold = startLoadValue,
                            phaseName = "loading",
                            isThresholdReached = { load -> load >= startLoadValue }
                        )
                    ) {
                        pushError("Cycle timed out while loading to $startLoadValue.")
                        return@launch
                    }

                    _uiState.update { it.copy(status = "Cycle holding load for 5s", error = null) }
                    delay(CYCLE_DWELL_MS)

                    if (!runCyclePhase(
                            direction = MOVE_DIRECTION_UP,
                            threshold = stopLoadValue,
                            phaseName = "unloading",
                            isThresholdReached = { load -> load <= stopLoadValue }
                        )
                    ) {
                        pushError("Cycle timed out while unloading to $stopLoadValue.")
                        return@launch
                    }

                    _uiState.update { it.copy(status = "Cycle holding unload for 5s", error = null) }
                    delay(CYCLE_DWELL_MS)
                }
            } catch (_: CancellationException) {
            } catch (ioe: IOException) {
                pushError("Cycle write error: ${ioe.message}")
                withContext(Dispatchers.Main) {
                    disconnect()
                }
            } catch (e: Exception) {
                pushError("Cycle failed: ${e.message}")
            } finally {
                cycleJob = null
                _uiState.update { state ->
                    if (state.isCycleRunning) {
                        state.copy(
                            isCycleRunning = false,
                            status = "Cycle mode stopped"
                        )
                    } else {
                        state
                    }
                }
            }
        }
    }

    private suspend fun runCyclePhase(
        direction: Int,
        threshold: Float,
        phaseName: String,
        isThresholdReached: (Float) -> Boolean
    ): Boolean {
        resetFailureTracking()
        _uiState.update {
            it.copy(
                chartPoints = emptyList(),
                failurePoint = null,
                status = "Cycle $phaseName started (graph cleared)",
                error = null
            )
        }

        val phaseStartedAtMs = System.currentTimeMillis()
        while (currentCoroutineContext().isActive) {
            val currentLoad = latestLoadCellReading
            if (currentLoad != null && isThresholdReached(currentLoad)) {
                _uiState.update {
                    it.copy(
                        status = "Cycle $phaseName reached $threshold (load=${formatDistance(currentLoad)})",
                        error = null
                    )
                }
                return true
            }

            if (System.currentTimeMillis() - phaseStartedAtMs >= CYCLE_PHASE_TIMEOUT_MS) {
                return false
            }

            val commandTimeMs = System.currentTimeMillis()
            writeControlPacket(
                packet = movePacket(direction = direction, distanceMm = CYCLE_STEP_MM),
                label = "Cycle $phaseName step",
                updateStatus = false
            )
            if (!waitForMotorToSettle(commandSentAtMs = commandTimeMs)) {
                return false
            }
        }
        return false
    }

    private fun stopCycle(updateStatus: Boolean) {
        val wasRunning = _uiState.value.isCycleRunning
        cycleJob?.cancel()
        cycleJob = null
        if (wasRunning) {
            _uiState.update { state ->
                state.copy(
                    isCycleRunning = false,
                    status = if (updateStatus) "Cycle mode stopped" else state.status
                )
            }
        }
    }

    private suspend fun waitForMotorToSettle(commandSentAtMs: Long): Boolean {
        val startAtMs = System.currentTimeMillis()
        var hasFreshSample = false
        var lastObservedPosition = latestMotorDistance
        var lastMovementAtMs = startAtMs

        while (currentCoroutineContext().isActive) {
            val nowMs = System.currentTimeMillis()
            val latestSampleAtMs = latestMotorDistanceUpdateAtMs
            val currentPosition = latestMotorDistance
            if (latestSampleAtMs >= commandSentAtMs && currentPosition != null) {
                if (!hasFreshSample) {
                    hasFreshSample = true
                    lastObservedPosition = currentPosition
                    lastMovementAtMs = nowMs
                } else if (
                    lastObservedPosition == null ||
                    abs(currentPosition - lastObservedPosition) > CYCLE_SETTLE_TOLERANCE_MM
                ) {
                    lastObservedPosition = currentPosition
                    lastMovementAtMs = nowMs
                }

                if (nowMs - lastMovementAtMs >= CYCLE_SETTLE_STABLE_WINDOW_MS) {
                    return true
                }
            }

            if (nowMs - startAtMs >= CYCLE_SETTLE_TIMEOUT_MS) {
                return false
            }

            delay(CYCLE_SETTLE_POLL_MS)
        }
        return false
    }

    private suspend fun writeControlPacket(packet: String, label: String, updateStatus: Boolean) {
        val activePort = port ?: throw IOException("Serial port is not open.")
        activePort.write(packet.toByteArray(Charsets.UTF_8), WRITE_TIMEOUT_MS)
        if (!updateStatus) return
        _uiState.update {
            it.copy(
                status = "$label command sent",
                error = null
            )
        }
    }

    private suspend fun writeControlPacket(packet: String, label: String) {
        writeControlPacket(packet = packet, label = label, updateStatus = true)
    }

    private fun movePacket(direction: Int, distanceMm: Float): String {
        return "$MOVE_TOOL,$direction,${formatDistance(distanceMm)}\n"
    }

    private fun formatDistance(distanceMm: Float): String {
        return String.format(Locale.US, "%.3f", distanceMm)
            .trimEnd('0')
            .trimEnd('.')
    }

    private fun sanitizeDecimalInput(value: String): String {
        val output = StringBuilder()
        var hasDecimal = false
        value.forEach { char ->
            when {
                char.isDigit() -> output.append(char)
                char == '.' && !hasDecimal -> {
                    hasDecimal = true
                    if (output.isEmpty()) output.append('0')
                    output.append('.')
                }
            }
        }
        return output.toString()
    }

    private fun pushError(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    private fun closePort() {
        stopCycle(updateStatus = false)
        readJob?.cancel()
        readJob = null
        resetFailureTracking()
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
        latestLoadCellReading = null
        latestMotorDistanceUpdateAtMs = 0L
        xZeroOffset = 0f
    }

    private fun detectFailurePoint(
        incomingPoint: ChartPoint,
        existing: FailurePointAnnotation?
    ): FailurePointAnnotation? {
        val existingMax = maxStrengthRawSinceReset ?: incomingPoint.y
        maxStrengthRawSinceReset = maxOf(existingMax, incomingPoint.y)

        val maxDropAcrossWindow = recentSamples.maxOfOrNull { it.y - incomingPoint.y } ?: 0f
        val isFailure = existing == null && maxDropAcrossWindow > FAILURE_DROP_THRESHOLD_UNITS
        val nextFailurePoint = if (isFailure) {
            FailurePointAnnotation(
                x = incomingPoint.x,
                y = incomingPoint.y,
                deformationMm = incomingPoint.x,
                maxStrengthKg = (maxStrengthRawSinceReset ?: incomingPoint.y) / LOAD_UNITS_PER_KG
            )
        } else {
            existing
        }

        recentSamples.addLast(incomingPoint)
        while (recentSamples.size > FAILURE_SAMPLE_WINDOW) {
            recentSamples.removeFirst()
        }
        return nextFailurePoint
    }

    private fun resetFailureTracking() {
        recentSamples.clear()
        maxStrengthRawSinceReset = null
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
        private const val MTFT_PACKET = "2,0,0\n"
        private const val CYCLE_STEP_MM = 0.1f
        private const val CYCLE_DWELL_MS = 5_000L
        private const val CYCLE_PHASE_TIMEOUT_MS = 120_000L
        private const val CYCLE_SETTLE_POLL_MS = 100L
        private const val CYCLE_SETTLE_TIMEOUT_MS = 45_000L
        private const val CYCLE_SETTLE_STABLE_WINDOW_MS = 500L
        private const val CYCLE_SETTLE_TOLERANCE_MM = 0.02f
        private const val FAILURE_DROP_THRESHOLD_UNITS = 50f
        private const val FAILURE_SAMPLE_WINDOW = 2
        private const val LOAD_UNITS_PER_KG = 10f

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
