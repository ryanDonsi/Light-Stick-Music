package com.lightstick.music.ui.components.device

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.theme.customTextStyles

/**
 * 빈 디바이스 카드 (Figma 디자인 적용)
 *
 * DeviceCard와 일관성을 위해 "Card" 네이밍 사용
 *
 * ## 상태별 표시:
 * - 연결된 기기 섹션 (비어있음): "연결된 기기가 없습니다."
 * - 연결할 기기 섹션 (스캔 중): "기기 검색 중..." + 로딩 애니메이션
 * - 연결할 기기 섹션 (스캔 완료, 비어있음): "검색된 기기가 없습니다" + 재검색 버튼
 *
 * @param isScanning 스캔 중 여부
 * @param isConnectedSection 연결된 기기 섹션인지 여부
 * @param onRefresh 재검색 버튼 클릭 시 콜백
 */
@Composable
fun EmptyDeviceCard(
    isScanning: Boolean,
    isConnectedSection: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp),  // ✅ Figma: 고정 높이
        shape = RoundedCornerShape(20.dp),  // ✅ Figma: Corner 20px
        color = MaterialTheme.customColors.onSurface.copy(alpha = 0.05f)  // ✅ Theme 적용
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize(),
//                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 텍스트
            Text(
                text = when {
                    isScanning && !isConnectedSection -> "기기 검색 중..."
                    isConnectedSection -> "연결된 기기가 없습니다"
                    else -> "검색된 기기가 없습니다"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (isConnectedSection) {
                    MaterialTheme.customColors.surfaceVariant  // ✅ 연결된 기기: surfaceVariant
                } else {
                    MaterialTheme.customColors.onSurface  // ✅ 검색된 기기: onSurface
                }
            )

//            Spacer(modifier = Modifier.width(12.dp))

            // 아이콘 (스캔 중 or 재검색 버튼)
            if (isScanning && !isConnectedSection) {
                // ✅ 스캔 중: 회전 애니메이션
                CircularProgressIndicator(
                    color = MaterialTheme.customColors.onSurface,  // ✅ onSurface 색상
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp
                )
            } else if (!isConnectedSection) {
                // ✅ 스캔 완료: 재검색 버튼
                IconButton(
                    onClick = { onRefresh?.invoke() },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "재검색",
                        tint = MaterialTheme.customColors.onSurface,  // ✅ onSurface 색상
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}