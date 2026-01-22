package com.lightstick.music.ui.components.device

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lightstick.music.ui.components.common.BaseDialog
import com.lightstick.music.ui.theme.customColors

/**
 * ═══════════════════════════════════════════════════════════════
 * 디바이스 관련 다이얼로그 모음 (Figma 디자인 적용)
 * ═══════════════════════════════════════════════════════════════
 *
 * 모든 다이얼로그는 BaseDialog를 사용하여 일관성 유지
 */

/**
 * 1. 연결 시도 다이얼로그
 *
 * Figma: "(기기명) 기기와 연결할까요?"
 * - subtitle만, content 없음
 * - 2버튼: "취소" / "확인"
 */
@Composable
fun ConnectConfirmDialog(
    deviceName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    BaseDialog(
        title = null,
        subtitle = "$deviceName 기기와 연결할까요?",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        confirmText = "확인",
        dismissText = "취소",
        scrollable = false,
        content = null
    )
}

/**
 * 2. 재연결 확인 다이얼로그
 *
 * Figma: "현재 (기 연결된 기기명칭) 기기가
 *         연결되어 있습니다.
 *         이 기기의 연결을 해제 후
 *         새로운 기기를 연결하시겠습니까?"
 * - subtitle만, content 없음
 * - 2버튼: "취소" / "확인"
 */
@Composable
fun ReconnectConfirmDialog(
    currentDeviceName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    BaseDialog(
        title = "연결",
        subtitle = "현재 $currentDeviceName 기기가 연결되어있습니다.\n" +
                "이 기기의 연결을 해제 후\n" +
                "새로운 기기를 연결하시겠습니까?",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        confirmText = "확인",
        dismissText = "취소",
        scrollable = false,
        content = null
    )
}

/**
 * 3. 연결 해제 확인 다이얼로그
 *
 * Figma: "(기기명) 기기 연결을 해제할까요?"
 * - subtitle만, content 없음
 * - 2버튼: "취소" / "확인"
 */
@Composable
fun DisconnectConfirmDialog(
    deviceName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    BaseDialog(
        title = null,
        subtitle = "$deviceName 기기 연결을 해제할까요?",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        confirmText = "확인",
        dismissText = "취소",
        scrollable = false,
        content = null
    )
}

/**
 * 4. 디바이스 정보 다이얼로그
 *
 * Figma: "디바이스 정보"
 * - title + content (정보 목록)
 * - 1버튼: "확인"
 */
@Composable
fun DeviceInfoDialog(
    model: String,
    firmware: String,
    manufacturer: String,
    onDismiss: () -> Unit
) {
    BaseDialog(
        title = "디바이스 정보",
        subtitle = null,
        onDismiss = onDismiss,
        confirmText = "확인",
        scrollable = false
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Model
            InfoRow(
                label = "Model",
                value = model
            )

            // Firmware
            InfoRow(
                label = "Firmware",
                value = firmware
            )

            // Manufacturer
            InfoRow(
                label = "Manufacturer",
                value = manufacturer
            )
        }
    }
}

/**
 * 정보 행 컴포넌트 (DeviceInfoDialog 내부 사용)
 */
@Composable
private fun InfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.customColors.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.customColors.onSurface
        )
    }
}


/**
 * 5. FIND 이펙트 전송 확인 다이얼로그
 *
 * Figma: "기기에 이펙트를 전송할까요?"
 * - subtitle만, content 없음
 * - 2버튼: "취소" / "확인"
 */
@Composable
fun FindEffectConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    BaseDialog(
        title = null,
        subtitle = "기기에 이펙트를 전송할까요?",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        confirmText = "확인",
        dismissText = "취소",
        scrollable = false,
        content = null
    )
}

/**
 * 6-1. 최신버전 다이얼로그
 *
 * Figma: "기기의 이름(버전)은 업데이트를 진행하시겠습니까?"
 * - subtitle만, content 없음
 * - 1버튼: "확인"
 */
@Composable
fun OtaVersionInfoDialog(
    deviceName: String,
    version: String,
    onDismiss: () -> Unit
) {
    BaseDialog(
        title = null,
        subtitle = "$deviceName($version)은\n최신 버전입니다.",
        onDismiss = onDismiss,
        confirmText = "확인",
        scrollable = false,
        content = null
    )
}

/**
 * 6-2. OTA 업데이트 확인 다이얼로그
 *
 * Figma: "기기의 이름(버전)은 업데이트를 진행하시겠습니까?"
 * - subtitle만, content 없음
 * - 2버튼: "취소" / "확인"
 */
@Composable
fun OtaUpdateConfirmDialog(
    deviceName: String,
    newversion: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    BaseDialog(
        title = null,
        subtitle = "$deviceName 를\n새로운 버전($newversion)으로 업데이트를\n진행하시겠습니까?",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        confirmText = "확인",
        dismissText = "취소",
        scrollable = false,
        content = null
    )
}