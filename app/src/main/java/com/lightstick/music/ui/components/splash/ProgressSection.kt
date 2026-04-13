package com.lightstick.music.ui.components.splash

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lightstick.music.ui.components.common.CommonProgressBar

/**
 * 스플래시 초기화 진행 섹션
 *
 * [CommonProgressBar]를 사용하여 초기화 단계별 진행률을 표시.
 *
 * @param title    단계 제목 텍스트
 * @param current  현재 처리 수 (null 이면 미표시)
 * @param total    전체 처리 수 (null 이면 미표시)
 * @param progress 진행률 0f..1f — null 이면 current/total 로 계산
 */
@Composable
fun ProgressSection(
    title: String,
    current: Int? = null,
    total: Int? = null,
    progress: Float? = null,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium
        )

        if (current != null && total != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "$current / $total",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        val calculatedProgress = progress ?: run {
            if (current != null && total != null && total > 0) {
                current.toFloat() / total
            } else {
                0f
            }
        }

        CommonProgressBar(
            progress = calculatedProgress,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        )
    }
}
