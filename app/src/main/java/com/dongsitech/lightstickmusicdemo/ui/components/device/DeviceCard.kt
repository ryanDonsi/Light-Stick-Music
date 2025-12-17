package com.dongsitech.lightstickmusicdemo.ui.components.device

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dongsitech.lightstickmusicdemo.model.DeviceDetailInfo
import com.lightstick.device.Device

/**
 * 디바이스 카드 컴포넌트
 *
 * @param device 디바이스
 * @param isConnected 연결 여부
 * @param deviceDetail 디바이스 상세 정보
 * @param canShowAddress 주소 표시 가능 여부
 * @param onToggleConnection 연결/해제 토글
 * @param onStartOta OTA 시작
 * @param onAbortOta OTA 중단
 * @param onToggleCallEvent Call Event 토글
 * @param onToggleSmsEvent SMS Event 토글
 * @param onToggleBroadcasting Broadcasting 토글
 */
@Composable
fun DeviceCard(
    device: Device,
    isConnected: Boolean,
    deviceDetail: DeviceDetailInfo?,
    canShowAddress: Boolean,
    onToggleConnection: () -> Unit,
    onStartOta: (Uri) -> Unit,
    onAbortOta: () -> Unit,
    onToggleCallEvent: (Boolean) -> Unit,
    onToggleSmsEvent: (Boolean) -> Unit,
    onToggleBroadcasting: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var showOtaDialog by remember { mutableStateOf(false) }

    val otaFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onStartOta(it) }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Device Name & Connection Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (isConnected) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
                            contentDescription = if (isConnected) "연결됨" else "미연결",
                            tint = if (isConnected) Color(0xFF2196F3) else Color.Gray,
                            modifier = Modifier.size(24.dp)
                        )

                        Text(
                            text = device.name ?: "Unknown Device",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    if (canShowAddress) {
                        Text(
                            text = "MAC: ${device.mac}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }

                    device.rssi?.let { rssi ->
                        Text(
                            text = "RSSI: $rssi dBm",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }

                // 연결/해제 버튼
                Button(
                    onClick = onToggleConnection,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isConnected) Color(0xFFE53935) else Color(0xFF2196F3)
                    )
                ) {
                    Text(if (isConnected) "해제" else "연결")
                }
            }

            // 연결된 경우 상세 정보
            if (isConnected && deviceDetail != null) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                // 확장/축소 버튼
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "상세 정보",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "접기" else "펼치기"
                        )
                    }
                }

                // 확장된 상세 정보
                if (expanded) {
                    DeviceDetailSection(
                        deviceDetail = deviceDetail,
                        onStartOta = { otaFileLauncher.launch("*/*") },
                        onAbortOta = onAbortOta,
                        onToggleCallEvent = onToggleCallEvent,
                        onToggleSmsEvent = onToggleSmsEvent,
                        onToggleBroadcasting = onToggleBroadcasting
                    )
                }
            }
        }
    }

    // OTA 다이얼로그 (필요시)
    if (showOtaDialog) {
        AlertDialog(
            onDismissRequest = { showOtaDialog = false },
            title = { Text("OTA 업데이트") },
            text = { Text("펌웨어 파일을 선택하세요.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showOtaDialog = false
                        otaFileLauncher.launch("*/*")
                    }
                ) {
                    Text("파일 선택")
                }
            },
            dismissButton = {
                TextButton(onClick = { showOtaDialog = false }) {
                    Text("취소")
                }
            }
        )
    }
}