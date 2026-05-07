package com.lightstick.music.ui.components.device

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lightstick.music.data.model.DeviceDetailInfo
import com.lightstick.music.ui.components.common.BaseButton
import com.lightstick.music.ui.components.common.ButtonStyle
import com.lightstick.music.ui.theme.customColors
import com.lightstick.device.Device
import com.lightstick.music.ui.components.common.CustomChip
import com.lightstick.music.ui.theme.surfaceGlass

/**
 * 디바이스 카드 컴포넌트 (Figma 디자인 100% 일치)
 *
 * ## Figma 속성:
 * - 크기: 343 x 94px (가로 고정, 세로 wrap)
 * - 패딩: top=12dp, end=20dp, bottom=12dp, start=24dp (비대칭)
 * - 모서리: 20dp
 * - MAC/RSSI 간격: 2dp
 * - 이름+배터리 간격: 8dp
 *
 * ## 디자인 규칙:
 * - 블루투스 아이콘 없음
 * - 연결된 기기:
 *   - 디바이스 이름 옆에 배터리 아이콘(ic_battery) + 퍼센트
 *   - 우측: 화살표(>) 아이콘만
 * - 미연결 기기:
 *   - 디바이스 이름만
 *   - 우측: "연결하기" 버튼 (회색)
 *
 * @param device 디바이스
 * @param isConnected 연결 여부
 * @param deviceDetail 디바이스 상세 정보 (배터리 정보 포함)
 * @param canShowAddress 주소 표시 가능 여부
 * @param onToggleConnection 연결/해제 토글
 * @param onNavigateToDetail 상세정보 화면으로 이동 (연결된 기기만)
 */
@Composable
fun DeviceCard(
    device: Device,
    isConnected: Boolean,
    deviceDetail: DeviceDetailInfo?,
    canShowAddress: Boolean,
    onToggleConnection: () -> Unit,
    onNavigateToDetail: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 글라스모피즘 카드 스타일
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.customColors.surfaceGlass
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 24.dp,
                    top = 12.dp,
                    end = 20.dp,
                    bottom = 12.dp
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 좌측: 디바이스 정보 (공통 컴포넌트 사용)
            DeviceInfoHeader(
                deviceName = device.name ?: "Unknown Device",
                batteryLevel = if (isConnected) deviceDetail?.batteryLevel else null,
                macAddress = device.mac,
                rssi = device.rssi,
                canShowAddress = canShowAddress,
                showBatteryBadge = isConnected,
                modifier = Modifier.weight(1f)
            )

            // 우측: 버튼 영역
            if (isConnected) {
                // 연결된 기기: 화살표 아이콘만
                IconButton(
                    onClick = onNavigateToDetail,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "상세정보",
                        tint = MaterialTheme.customColors.onSurface
                    )
                }
            } else {
                // 미연결 기기: "연결하기" 버튼
                CustomChip(
                    text = "연결하기",
                    onClick = onToggleConnection,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}