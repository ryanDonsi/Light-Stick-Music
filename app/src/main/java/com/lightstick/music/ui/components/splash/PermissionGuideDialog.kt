package com.lightstick.music.ui.components.splash

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lightstick.music.R
import com.lightstick.music.ui.components.common.BaseDialog
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.theme.customTextStyles

/**
 * 권한 설정 안내 다이얼로그
 * - BaseDialog를 사용하여 일관된 UI 제공 (1-버튼 버전)
 * - 권한이 필요한 이유와 각 권한의 용도를 설명
 * - 권한이 없을 때만 표시됨
 */
@Composable
fun PermissionGuideDialog(
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    BaseDialog(
        title = "권한 설정",
        subtitle = null,
        onDismiss = onConfirm,  // 확인 버튼과 동일한 동작
        confirmText = "확인",
        scrollable = true,
        dismissible = false  // 권한 없이는 앱 사용 불가하므로 뒤로가기/외부클릭 막기
    ) {
        // 권한 목록
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 안내 문구
            Text(
                text = "원활한 앱 사용을 위해 아래의 접근 권한이 필요합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.customColors.onSurfaceVariant,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )

            // 1. 근처 기기 액세스 (필수)
            PermissionItem(
                iconRes = R.drawable.ic_bluetooth,
                title = "근처 기기 액세스 (필수)",
                description = "블루투스 기기를 이용하여 근처 기기를 찾아서 연결하고 통신을 합니다."
            )

            // 2. 저장 공간 (필수)
            PermissionItem(
                iconRes = R.drawable.ic_save,
                title = "저장 공간 (필수)",
                description = "앱에 필요한 정보를 디바이스에 저장합니다."
            )

            // 3. 위치 (필수)
            PermissionItem(
                iconRes = R.drawable.ic_location,
                title = "위치 (필수)",
                description = "앱을 닫거나 백그라운드에 있을 때에도 위치 데이터를 수집하여 디바이스 블루투스 통신 기능을 활성화 합니다."
            )

            // 4. 알림 (선택)
            PermissionItem(
                iconRes = R.drawable.ic_alram,
                title = "알림 (선택)",
                description = "디바이스와 동작 상태를 표시합니다."
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 안내 문구
        Text(
            text = "* 접근 권한은 설정 > 어플리케이션 > GLOWSYNC에서 변경 가능합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.customColors.onSurfaceVariant,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )
    }
}