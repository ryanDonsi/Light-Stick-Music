package com.lightstick.music.ui.components.device

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lightstick.music.ui.components.common.CommonProgressBar
import com.lightstick.music.ui.theme.customColors

/**
 * OTA 펌웨어 업데이트 진행 섹션
 *
 * [CommonProgressBar]를 사용하여 업데이트 진행률을 표시하고,
 * 중단 버튼을 포함한 OTA 전용 레이아웃을 제공.
 *
 * @param progress 진행률 0..100
 * @param onAbort  OTA 중단 콜백
 */
@Composable
fun OtaProgressSection(
    progress: Int,
    onAbort: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "OTA 진행 중",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        CommonProgressBar(
            progress = progress / 100f,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "$progress%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.customColors.textTertiary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onAbort,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.customColors.error
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("OTA 중단")
        }
    }
}
