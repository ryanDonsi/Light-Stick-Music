package com.lightstick.music.presentation.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lightstick.music.presentation.theme.customColors
import com.lightstick.music.presentation.theme.customTextStyles

/**
 * ✅ ButtonStyle - 버튼 스타일 타입
 */
enum class ButtonStyle {
    /** Primary 버튼 - 보라색 (#843DFF) */
    PRIMARY,

    /** Surface 버튼 - 회색 (#6F7074) */
    SURFACE
}

/**
 * ✅ ButtonSize - 버튼 크기 타입
 */
enum class ButtonSize {
    /** Small - 90 × 44px (Figma 기본) */
    SMALL
}

/**
 * ✅ BaseButton - 공통 버튼 컴포넌트
 *
 * ## Figma 스펙
 * - 크기: 90px × 44px (Small)
 * - 높이 제약: 최소 42px, 최대 56px
 * - Corner Radius: 8px
 * - Padding: 12px
 * - 색상:
 *   - Primary: #843DFF
 *   - Surface: #6F7074
 *   - Disabled: #6C6C6C
 *
 * ## 상태
 * - Normal: 기본 상태
 * - Pressed: Material3가 자동 처리 (약간 어두워짐)
 * - Disabled: enabled = false
 *
 * ## 높이 제약
 * - 최소 높이: 42dp (너무 작아지지 않음)
 * - 최대 높이: 56dp (너무 커지지 않음)
 * - 기본 높이: 44dp
 *
 * ## 사용 예시
 * ```kotlin
 * // Primary 버튼
 * BaseButton(
 *     text = "확인",
 *     onClick = { }
 * )
 *
 * // Surface 버튼
 * BaseButton(
 *     text = "취소",
 *     onClick = { },
 *     style = ButtonStyle.SURFACE
 * )
 *
 * // Disabled 버튼
 * BaseButton(
 *     text = "비활성",
 *     onClick = { },
 *     enabled = false
 * )
 *
 * // Loading 버튼
 * BaseButton(
 *     text = "처리중",
 *     onClick = { },
 *     loading = true
 * )
 *
 * // 높이 커스텀 (제약 내에서)
 * BaseButton(
 *     text = "큰 버튼",
 *     onClick = { },
 *     modifier = Modifier.height(50.dp)  // 42 ≤ 50 ≤ 56
 * )
 *
 * // Full Width 버튼
 * BaseButton(
 *     text = "전체 너비",
 *     onClick = { },
 *     modifier = Modifier.fillMaxWidth()
 * )
 * ```
 *
 * @param text 버튼 텍스트
 * @param onClick 클릭 이벤트
 * @param modifier 크기/위치 조정
 * @param enabled 활성/비활성 상태 (기본 true)
 * @param loading 로딩 상태 (기본 false)
 * @param style 버튼 스타일 (기본 PRIMARY)
 * @param size 버튼 크기 (기본 SMALL)
 */
@Composable
fun BaseButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    style: ButtonStyle = ButtonStyle.PRIMARY,
    size: ButtonSize = ButtonSize.SMALL
) {
    val buttonColors = getButtonColors(style)
    val buttonSize = getButtonSize(size)

    Button(
        onClick = onClick,
        enabled = enabled && !loading,  // Loading 중에는 비활성화
        modifier = modifier
            .then(buttonSize.modifier),  // 크기 적용
        colors = buttonColors,
        shape = RoundedCornerShape(8.dp),  // ✅ Figma: Corner 8px
        contentPadding = PaddingValues(
            horizontal = 12.dp,  // ✅ Figma: Padding 12px
            vertical = 12.dp
        )
    ) {
        if (loading) {
            // ✅ Loading 상태
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
        } else {
            // ✅ Normal 상태
            Text(
                text = text,
                style = MaterialTheme.customTextStyles.buttonSmall,
                color = MaterialTheme.customColors.onPrimaryContainer
            )
        }
    }
}

/**
 * ✅ BaseButton with Icon - 아이콘 포함 버튼
 *
 * @param text 버튼 텍스트
 * @param onClick 클릭 이벤트
 * @param icon 아이콘 Composable
 * @param modifier 크기/위치 조정
 * @param enabled 활성/비활성 상태
 * @param loading 로딩 상태
 * @param style 버튼 스타일
 * @param size 버튼 크기
 */
@Composable
fun BaseButton(
    text: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    style: ButtonStyle = ButtonStyle.PRIMARY,
    size: ButtonSize = ButtonSize.SMALL
) {
    val buttonColors = getButtonColors(style)
    val buttonSize = getButtonSize(size)

    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .then(buttonSize.modifier),
        colors = buttonColors,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(
            horizontal = 12.dp,
            vertical = 12.dp
        )
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),  // ✅ Figma: 간격 8px
                verticalAlignment = Alignment.CenterVertically
            ) {
                icon()
                Text(
                    text = text,
                    style = MaterialTheme.customTextStyles.buttonSmall,
                    color = MaterialTheme.customColors.onPrimaryContainer
                )
            }
        }
    }
}

/**
 * ✅ 버튼 스타일별 색상 반환
 */
@Composable
private fun getButtonColors(style: ButtonStyle): ButtonColors {
    return when (style) {
        ButtonStyle.PRIMARY -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            disabledContainerColor = MaterialTheme.customColors.disable,
            disabledContentColor = MaterialTheme.customColors.onDisable.copy(alpha = 0.3f)
        )
        ButtonStyle.SURFACE -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.customColors.disable,
            disabledContentColor = MaterialTheme.customColors.onDisable.copy(alpha = 0.3f)
        )
    }
}

/**
 * ✅ 버튼 크기 정보
 */
private data class ButtonSizeInfo(
    val modifier: Modifier
)

/**
 * ✅ 버튼 크기별 Modifier 반환
 */
private fun getButtonSize(size: ButtonSize): ButtonSizeInfo {
    return when (size) {
        ButtonSize.SMALL -> ButtonSizeInfo(
            modifier = Modifier
                .width(90.dp)   // ✅ Figma: 90px
                .heightIn(min = 42.dp, max = 56.dp)  // ✅ Figma: 최소 42px, 최대 56px
                .height(44.dp)  // ✅ Figma: 기본 44px
        )
    }
}