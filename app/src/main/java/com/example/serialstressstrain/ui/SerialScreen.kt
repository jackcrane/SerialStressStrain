package com.example.serialstressstrain.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.log.size) {
        if (state.log.isNotEmpty()) {
            listState.animateScrollToItem(state.log.lastIndex)
        }
    }

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

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Incoming data",
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp
            )

            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (state.log.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Nothing received yet.")
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.log) { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
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
                log = listOf("Sample data line 1", "Sample data line 2")
            ),
            onRefresh = {},
            onDeviceSelected = {},
            onBaudChange = {},
            onConnect = {},
            onDisconnect = {}
        )
    }
}
