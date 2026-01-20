package com.lightstick.music.ui.components.device

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lightstick.music.R
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.theme.customTextStyles

/**
 * 디바이스 정보 헤더 컴포넌트
 *
 * DeviceCard와 DeviceDetailScreen에서 공통으로 사용
 *
 * ## 구성:
 * - 디바이스 이름 + 배터리 정보
 * - MAC 주소 (3영역 정렬: "MAC | xx:xx:xx:xx:xx:xx")
 * - RSSI (3영역 정렬: "RSSI | -XX dBm")
 *
 * @param deviceName 디바이스 이름
 * @param batteryLevel 배터리 레벨 (nullable, 연결된 경우만)
 * @param macAddress MAC 주소
 * @param rssi RSSI 값 (nullable)
 * @param canShowAddress MAC 주소 표시 가능 여부
 * @param showBatteryBadge 배터리 배지 표시 여부 (연결된 경우만)
 */
@Composable
fun DeviceInfoHeader(
    deviceName: String,
    batteryLevel: Int?,
    macAddress: String,
    rssi: Int?,
    canShowAddress: Boolean,
    showBatteryBadge: Boolean = true,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // 디바이스 이름 + 배터리
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = deviceName.ifEmpty { "Unknown Device" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.customColors.onSurface
            )

            // 배터리 정보 (연결된 경우만)
            if (showBatteryBadge && batteryLevel != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.wrapContentSize()
                ) {
                    // 배경: 배터리 아이콘
                    Icon(
                        painter = painterResource(id = R.drawable.ic_battery),
                        contentDescription = "배터리",
                        tint = Color(0xFFD9D9D9)
                    )
                    // 전경: 배터리 퍼센트 텍스트
                    Text(
                        text = "$batteryLevel%",
                        style = MaterialTheme.customTextStyles.badgeSmall,
                        color = MaterialTheme.customColors.surface
                    )
                }
            }
        }

        // MAC Address (3영역 정렬)
        if (canShowAddress) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MAC",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.customColors.onSurfaceVariant,
                    modifier = Modifier.width(34.dp)
                )
                Text(
                    text = "|",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.customColors.onSurfaceVariant
                )
                Text(
                    text = macAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.customColors.onSurfaceVariant
                )
            }
        }

        // RSSI (3영역 정렬)
        rssi?.let {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "RSSI",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.customColors.onSurfaceVariant,
                    modifier = Modifier.width(34.dp)
                )
                Text(
                    text = "|",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.customColors.onSurfaceVariant
                )
                Text(
                    text = "$it dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.customColors.onSurfaceVariant
                )
            }
        }
    }
}