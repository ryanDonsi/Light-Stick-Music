package com.lightstick.music.presentation.components.device

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lightstick.music.data.model.DeviceDetailInfo

/**
 * 디바이스 상세 정보 섹션
 */
@Composable
fun DeviceDetailSection(
    deviceDetail: DeviceDetailInfo,
    onStartOta: () -> Unit,
    onAbortOta: () -> Unit,
    onToggleCallEvent: (Boolean) -> Unit,
    onToggleSmsEvent: (Boolean) -> Unit,
    onToggleBroadcasting: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Device Info
        deviceDetail.deviceInfo?.let { info ->
            info.modelNumber?.let {
                DetailRow("모델", it)
            }
            info.firmwareRevision?.let {
                DetailRow("펌웨어", it)
            }
            info.manufacturer?.let {
                DetailRow("제조사", it)
            }
        }

        // Battery Level
        deviceDetail.batteryLevel?.let { batteryLevel ->
            DetailRow("배터리", "$batteryLevel%")
        }

        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(4.dp))

        // Event Settings
        Text(
            text = "이벤트 설정",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )

        SwitchRow(
            label = "Call Event",
            checked = deviceDetail.callEventEnabled,
            onCheckedChange = onToggleCallEvent
        )

        SwitchRow(
            label = "SMS Event",
            checked = deviceDetail.smsEventEnabled,
            onCheckedChange = onToggleSmsEvent
        )

        SwitchRow(
            label = "Broadcasting",
            checked = deviceDetail.broadcasting,
            onCheckedChange = onToggleBroadcasting
        )

        // OTA Section
        if (deviceDetail.isOtaInProgress && deviceDetail.otaProgress != null) {
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "OTA 진행 중",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { deviceDetail.otaProgress / 100f },  // ✅ 람다로 수정
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "${deviceDetail.otaProgress}%",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onAbortOta,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE53935)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("OTA 중단")
            }
        } else {
            // OTA 시작 버튼
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onStartOta,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("OTA 업데이트")
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}