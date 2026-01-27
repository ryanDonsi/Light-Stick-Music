package com.lightstick.music.ui.components.init

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lightstick.music.ui.theme.customColors

/**
 * 텍스트 전용 섹션 컴포넌트
 * - ProgressSection과 동일한 레이아웃
 * - LinearProgressIndicator 없이 텍스트만 표시
 * - 일관된 높이/간격 유지
 */
@Composable
fun TextSection(
    title: String,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth()
    ) {
        // 타이틀 텍스트
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.customColors.onSurface
        )

        // ✅ ProgressSection과 동일한 spacing 유지
        Spacer(modifier = Modifier.height(8.dp))

        // ✅ LinearProgressIndicator 높이만큼 공간 확보 (4dp)
        // 이렇게 하면 ProgressSection과 동일한 높이 유지
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .height(4.dp)
        )
    }
}