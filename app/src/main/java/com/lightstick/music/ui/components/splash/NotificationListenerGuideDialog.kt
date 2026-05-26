package com.lightstick.music.ui.components.splash

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.lightstick.music.ui.components.common.BaseDialog
import com.lightstick.music.ui.theme.customColors

@Composable
fun NotificationListenerGuideDialog(
    onDismiss: () -> Unit,
    onGoToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    BaseDialog(
        title = "알림 접근 권한",
        subtitle = null,
        onDismiss = onDismiss,
        onConfirm = onGoToSettings,
        confirmText = "설정으로 이동",
        dismissText = "나중에",
        scrollable = false,
        dismissible = true
    ) {
        Text(
            text = "전화 수신 및 메시지 알림 이펙트를 사용하려면 알림 접근 권한이 필요합니다.\n\n설정 > 앱 > 특별 앱 접근 > 알림 접근에서 해당 앱을 허용해 주세요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.customColors.onSurfaceVariant,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
