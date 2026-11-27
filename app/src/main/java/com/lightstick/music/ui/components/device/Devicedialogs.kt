package com.lightstick.music.ui.components.device

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lightstick.music.R
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
    name: String,
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
            InfoRow(
                label = "Name",
                value = name
            )

            InfoRow(
                label = "Model",
                value = model
            )

            InfoRow(
                label = "Firmware",
                value = firmware
            )

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
 * 5-0. 공연 제어 미지원 안내 다이얼로그
 *
 * 공연 모드 / SYSTEM OFF는 Control(FF0A, 펌웨어 2.200 이상) 지원 기기에서만 동작한다.
 * - 1버튼: "확인"
 */
@Composable
fun ControlUnsupportedDialog(
    onDismiss: () -> Unit
) {
    BaseDialog(
        title = "지원하지 않는 기능",
        subtitle = "연결된 기기가 공연 제어를 지원하지 않습니다.\n" +
                "펌웨어를 2.200 이상으로 업데이트한 뒤\n" +
                "사용해 주세요.",
        onDismiss = onDismiss,
        confirmText = "확인",
        scrollable = false,
        content = null
    )
}

/**
 * 5-1. 전원 끄기(SYSTEM_OFF) 확인 다이얼로그
 *
 * 복구 불가 동작이므로 범위와 결과를 명시하고, 기본 포커스 동작(바깥 터치/뒤로가기 닫기)은 유지한다.
 * - 2버튼: "취소" / "전원 끄기"
 */
@Composable
fun SystemOffConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    BaseDialog(
        title = "전원 끄기",
        subtitle = "신호가 닿는 모든 응원봉의 전원이 꺼집니다.\n" +
                "꺼진 응원봉은 앱에서 다시 켤 수 없고,\n" +
                "응원봉의 버튼으로 직접 켜야 합니다.\n\n" +
                "전원을 끌까요?",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        confirmText = "전원 끄기",
        dismissText = "취소",
        scrollable = false,
        content = null
    )
}

/**
 * 이펙트 폴더 미설정 안내 다이얼로그
 *
 * 앱 최초 실행 또는 SAF 폴더 미설정 상태일 때 표시
 * - 확인: 폴더 선택 피커 실행
 * - 취소: 다음에 설정 (FFT 모드로 동작)
 */
@Composable
fun EffectFolderGuideDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    BaseDialog(
        title = "이펙트 폴더 설정",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        confirmText = "폴더 설정",
        dismissText = "나중에",
        scrollable = false
    ) {
        Text(
            text = "EFX 파일이 저장된 폴더를 지정하면\n음악 재생 시 EFX 이펙트가 연동됩니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.customColors.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.effect_folder_picker_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.customColors.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
