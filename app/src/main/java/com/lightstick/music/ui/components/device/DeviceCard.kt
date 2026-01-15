package com.lightstick.music.ui.components.device

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lightstick.music.R
import com.lightstick.music.data.model.DeviceDetailInfo
import com.lightstick.music.ui.components.common.BaseButton
import com.lightstick.music.ui.components.common.ButtonStyle
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.theme.surfaceGlass
import com.lightstick.music.ui.theme.surfaceGlassBorder
import com.lightstick.device.Device

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
        shape = RoundedCornerShape(20.dp),  // ✅ Figma: 20dp
        color = MaterialTheme.customColors.surfaceGlass
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    // ✅ Figma: 비대칭 패딩
                    start = 24.dp,
                    top = 12.dp,
                    end = 20.dp,
                    bottom = 12.dp
                )
        ) {
            // ===== 디바이스 정보 영역 =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 좌측: 디바이스 정보
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)  // ✅ Figma: 2dp
                ) {
                    // 디바이스 이름 + 배터리 (연결된 경우)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),  // ✅ Figma: 8dp
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = device.name ?: "Unknown Device",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.customColors.onSurface
                        )

                        // 연결된 경우 배터리 정보 표시 (아이콘 위에 텍스트 오버레이)
                        if (isConnected && deviceDetail?.batteryLevel != null) {
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
                                // 전경: 배터리 퍼센트 텍스트
                                Text(
                                    text = "${deviceDetail.batteryLevel}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.customColors.onSurface
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
                                modifier = Modifier.width(34.dp)  // ✅ Figma: 고정 너비
                            )
                            Text(
                                text = "|",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.customColors.onSurfaceVariant
                            )
                            Text(
                                text = device.mac,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.customColors.onSurfaceVariant
                            )
                        }
                    }

                    // RSSI (3영역 정렬)
                    device.rssi?.let { rssi ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "RSSI",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.customColors.onSurfaceVariant,
                                modifier = Modifier.width(34.dp)  // ✅ Figma: 고정 너비 (MAC과 동일)
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
                    BaseButton(
                        text = "연결하기",
                        onClick = onToggleConnection,
                        style = ButtonStyle.SURFACE,
                        modifier = Modifier
                            .width(80.dp)
                            .height(42.dp)
                    )
                }
            }
        }
    }
}