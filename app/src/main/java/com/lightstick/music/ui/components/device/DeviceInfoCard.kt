package com.lightstick.music.ui.components.device

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lightstick.music.R
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.theme.surfaceGlass
import com.lightstick.music.ui.theme.surfaceGlassBorder

/**
 * 디바이스 정보 카드 컴포넌트 (Figma 디자인 적용)
 *
 * DeviceDetailScreen에서 사용하는 정보 표시 전용 카드
 * DeviceCard와 달리 버튼이 없고 정보만 표시
 *
 * ## Figma 속성:
 * - 패딩: start=24dp, top=12dp, end=20dp, bottom=12dp
 * - 모서리: 20dp
 * - MAC/RSSI 간격: 2dp
 * - 배터리: Box 오버레이
 *
 * @param deviceName 디바이스 이름
 * @param batteryLevel 배터리 레벨 (nullable, 있으면 배지 표시)
 * @param macAddress MAC 주소
 * @param rssi RSSI 값
 */
@Composable
fun DeviceInfoCard(
    deviceName: String,
    batteryLevel: Int?,
    macAddress: String,
    rssi: Int,
    modifier: Modifier = Modifier
) {
    // 글라스모피즘 카드 스타일
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.customColors.surfaceGlass,
        border = BorderStroke(1.dp, MaterialTheme.customColors.surfaceGlassBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 24.dp,
                    top = 12.dp,
                    end = 20.dp,
                    bottom = 12.dp
                ),
            verticalArrangement = Arrangement.spacedBy(2.dp)  // ✅ Figma: 2dp
        ) {
            // 디바이스 이름 + 배터리
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.customColors.onSurface
                )

                // 배터리 배지 (있는 경우)
                batteryLevel?.let { level ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(width = 40.dp, height = 20.dp)
                    ) {
                        // 배경: 배터리 아이콘
                        Icon(
                            painter = painterResource(id = R.drawable.ic_battery),
                            contentDescription = "배터리",
                            modifier = Modifier.fillMaxSize(),
                            tint = MaterialTheme.customColors.primary
                        )
                        // 전경: 배터리 퍼센트
                        Text(
                            text = "$level%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.customColors.onSurface
                        )
                    }
                }
            }

            // MAC Address (3영역 정렬)
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

            // RSSI (3영역 정렬)
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
                    text = "$rssi dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.customColors.onSurfaceVariant
                )
            }
        }
    }
}