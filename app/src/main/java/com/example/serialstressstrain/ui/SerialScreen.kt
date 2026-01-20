package com.example.serialstressstrain.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.serialstressstrain.ChartPoint
import com.example.serialstressstrain.SerialDeviceUi
import com.example.serialstressstrain.SerialUiState
import com.example.serialstressstrain.ui.theme.SerialStressStrainTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SerialScreen(
    state: SerialUiState,
    onRefresh: () -> Unit,
    onDeviceSelected: (Int?) -> Unit,
    onBaudChange: (String) -> Unit,
    onSampleWindowChange: (String) -> Unit,
    onYMinChange: (String) -> Unit,
    onYMaxChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Serial Monitor") })
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Pick a USB serial device and baud rate, then connect to start reading data.",
                style = MaterialTheme.typography.bodyMedium
            )

            DeviceDropdown(
                devices = state.devices,
                selectedDeviceId = state.selectedDeviceId,
                onDeviceSelected = onDeviceSelected
            )

            OutlinedTextField(
                value = state.baudRate,
                onValueChange = onBaudChange,
                label = { Text("Baud rate") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onRefresh,
                    enabled = !state.isConnecting
                ) {
                    Text("Refresh")
                }
                val connectLabel = when {
                    state.isConnected -> "Disconnect"
                    state.isConnecting -> "Connecting..."
                    else -> "Connect"
                }
                Button(
                    onClick = { if (state.isConnected) onDisconnect() else onConnect() },
                    enabled = (!state.isConnecting && state.selectedDeviceId != null)
                ) {
                    Text(connectLabel)
                }
            }

            Text(
                text = "Status: ${state.status}",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            state.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Live graph (packetType 0)",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Showing last ${state.chartPoints.size} pts",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = state.sampleWindow,
                            onValueChange = onSampleWindowChange,
                            label = { Text("Samples to keep (e.g. 10000)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Next
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = state.yMin,
                            onValueChange = onYMinChange,
                            label = { Text("Y min") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Next
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = state.yMax,
                            onValueChange = onYMaxChange,
                            label = { Text("Y max") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Done
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    LineGraph(
                        points = state.chartPoints,
                        yMin = state.yMin.toFloatOrNull(),
                        yMax = state.yMax.toFloatOrNull(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceDropdown(
    devices: List<SerialDeviceUi>,
    selectedDeviceId: Int?,
    onDeviceSelected: (Int?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedDevice = devices.firstOrNull { it.id == selectedDeviceId }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedDevice?.title ?: "Select a USB device",
            onValueChange = {},
            readOnly = true,
            label = { Text("USB device") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            if (devices.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No USB devices detected") },
                    onClick = { expanded = false }
                )
            } else {
                devices.forEach { device ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(device.title, fontWeight = FontWeight.SemiBold)
                                Text(
                                    device.subtitle,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        },
                        onClick = {
                            onDeviceSelected(device.id)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LineGraph(
    points: List<ChartPoint>,
    yMin: Float?,
    yMax: Float?,
    modifier: Modifier = Modifier
) {
    if (points.isEmpty()) {
        Column(
            modifier = modifier.padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("No data yet.")
        }
        return
    }

    val xDataMin = points.first().x
    val xDataMax = points.last().x
    val yDataMin = points.minOf { it.y }
    val yDataMax = points.maxOf { it.y }

    val resolvedYMin = yMin ?: yDataMin
    val resolvedYMax = yMax ?: yDataMax
    val autoPadding = (resolvedYMax - resolvedYMin).let { if (it <= 0f) 1f else it * 0.05f }
    val adjustedYMin = if (yMin != null || yMax != null) resolvedYMin else resolvedYMin - autoPadding
    val adjustedYMax = if (yMin != null || yMax != null) resolvedYMax else resolvedYMax + autoPadding
    val lineColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.outlineVariant

    androidx.compose.foundation.Canvas(modifier = modifier) {
        val xRange = (xDataMax - xDataMin).let { if (it <= 0f) 1f else it }
        val yRange = (adjustedYMax - adjustedYMin).let { if (it <= 0f) 1f else it }

        val path = Path()
        points.forEachIndexed { index, point ->
            val xPos = ((point.x - xDataMin) / xRange) * size.width
            val yPos = size.height - ((point.y - adjustedYMin) / yRange) * size.height
            if (index == 0) {
                path.moveTo(xPos, yPos)
            } else {
                path.lineTo(xPos, yPos)
            }
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 4f, cap = StrokeCap.Round)
        )
        drawLine(
            color = axisColor,
            start = androidx.compose.ui.geometry.Offset(0f, size.height - 1f),
            end = androidx.compose.ui.geometry.Offset(size.width, size.height - 1f),
            strokeWidth = 2f
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SerialScreenPreview() {
    SerialStressStrainTheme {
        SerialScreen(
            state = SerialUiState(
                devices = listOf(
                    SerialDeviceUi(1, "Test Device", "VID:1234 PID:5678")
                ),
                selectedDeviceId = 1,
                baudRate = "9600",
                status = "Disconnected",
                chartPoints = listOf(
                    ChartPoint(1f, 2f),
                    ChartPoint(2f, 3f),
                    ChartPoint(3f, 2.5f),
                    ChartPoint(4f, 4f)
                ),
                sampleWindow = "10000",
                yMin = "0",
                yMax = "5"
            ),
            onRefresh = {},
            onDeviceSelected = {},
            onBaudChange = {},
            onSampleWindowChange = {},
            onYMinChange = {},
            onYMaxChange = {},
            onConnect = {},
            onDisconnect = {}
        )
    }
}
