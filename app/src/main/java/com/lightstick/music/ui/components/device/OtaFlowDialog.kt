package com.lightstick.music.ui.components.device

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lightstick.music.ui.components.common.BaseDialog
import com.lightstick.music.ui.components.common.CommonProgressBar
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.viewmodel.DeviceViewModel.OtaUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * OTA 전체 흐름을 하나의 대화상자로 보여 준다: 확인 → 진행 → 재부팅 대기 → 결과.
 *
 * 펌웨어 버전 정보가 아직 없어 "새 버전" 비교는 하지 않고, 고른 파일의 정보를 보여 준다.
 * 진행·재부팅 단계는 바깥 터치·뒤로가기로 닫히지 않는다.
 *
 * @param onConfirm 확인 단계 "업데이트 시작" / 실패 단계 "다시 시도"
 * @param onAbort   중지 확인까지 받은 뒤 호출
 * @param onPickAnotherFile 파일 오류 단계에서 다른 파일 선택
 * @param onClose   결과·오류·취소로 대화상자를 닫을 때
 */
@Composable
fun OtaFlowDialog(
    state: OtaUi,
    onConfirm: () -> Unit,
    onAbort: () -> Unit,
    onPickAnotherFile: () -> Unit,
    onClose: () -> Unit
) {
    var askAbort by remember { mutableStateOf(false) }

    when (state) {
        is OtaUi.FileError -> BaseDialog(
            title       = "펌웨어 파일 확인",
            subtitle    = state.message,
            onDismiss   = onClose,
            onConfirm   = onPickAnotherFile,
            confirmText = "다른 파일 선택",
            dismissText = "닫기",
            scrollable  = false
        )

        is OtaUi.Confirm -> BaseDialog(
            title       = "펌웨어 업데이트",
            subtitle    = null,
            onDismiss   = onClose,
            onConfirm   = onConfirm,
            confirmText = "업데이트 시작",
            dismissText = "취소",
            scrollable  = false
        ) {
            InfoLine("기기", state.deviceName + (state.currentVersion?.let { " (현재 $it)" } ?: ""))
            InfoLine("파일", state.fileName)
            InfoLine(
                "크기",
                formatSize(state.sizeBytes) + (state.modifiedAtMs?.let { " · ${formatDate(it)} 수정" } ?: "")
            )
            Spacer(Modifier.height(8.dp))
            NoteText("이 파일로 덮어씁니다. 같은 버전이어도 진행됩니다.")
            if (state.unusualExtension) {
                WarningText("확장자가 .bin 이 아닙니다. 펌웨어 파일이 맞는지 확인해 주세요.")
            }
            if (state.lowBattery) {
                WarningText("응원봉 배터리가 부족합니다. 충전한 뒤 진행하는 것을 권장합니다.")
            }
        }

        is OtaUi.Transferring -> {
            BaseDialog(
                title       = "펌웨어 업데이트 중",
                subtitle    = null,
                onDismiss   = { askAbort = true },
                confirmText = "중지",
                scrollable  = false,
                dismissible = false
            ) {
                Text(
                    text       = "${state.percent}%",
                    style      = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier   = Modifier.fillMaxWidth(),
                    textAlign  = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                CommonProgressBar(
                    progress = state.percent / 100f,
                    modifier = Modifier.fillMaxWidth(),
                    height   = 6.dp
                )
                Spacer(Modifier.height(8.dp))
                state.etaSeconds?.let {
                    NoteText("남은 시간 약 ${formatEta(it)}", center = true)
                }
                NoteText("앱을 닫거나 응원봉을 끄지 마세요.", center = true)
            }
            if (askAbort) {
                BaseDialog(
                    title       = "업데이트를 중지할까요?",
                    subtitle    = "중지하면 응원봉이 꺼집니다. 다시 켜서 연결한 뒤 업데이트를 다시 진행해 주세요.",
                    onDismiss   = { askAbort = false },
                    onConfirm   = { askAbort = false; onAbort() },
                    confirmText = "중지",
                    dismissText = "계속 진행",
                    scrollable  = false
                )
            }
        }

        is OtaUi.Rebooting -> BaseDialog(
            title       = "응원봉이 다시 시작하는 중",
            subtitle    = "전송이 끝났습니다. 다시 연결될 때까지 잠시 기다려 주세요.",
            onDismiss   = {},
            confirmText = "기다리는 중…",
            scrollable  = false,
            dismissible = false
        )

        is OtaUi.Success -> BaseDialog(
            title       = "업데이트 완료",
            subtitle    = when {
                state.version != null -> "${state.deviceName} · 현재 ${state.version}"
                state.reconnected     -> "${state.deviceName} 업데이트가 끝났습니다."
                else                  -> "업데이트가 끝났습니다. 응원봉이 다시 연결되지 않으면 켜고 다시 연결해 주세요."
            },
            onDismiss   = onClose,
            confirmText = "확인",
            scrollable  = false
        )

        is OtaUi.Failed -> if (state.canRetry) {
            BaseDialog(
                title       = "업데이트 실패",
                subtitle    = state.message,
                onDismiss   = onClose,
                onConfirm   = onConfirm,
                confirmText = "다시 시도",
                dismissText = "닫기",
                scrollable  = false
            )
        } else {
            BaseDialog(
                title       = "업데이트 중지됨",
                subtitle    = state.message,
                onDismiss   = onClose,
                confirmText = "확인",
                scrollable  = false
            )
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodyMedium,
            color    = MaterialTheme.customColors.textTertiary,
            modifier = Modifier.width(48.dp)
        )
        Text(
            text  = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun NoteText(text: String, center: Boolean = false) {
    Text(
        text      = text,
        style     = MaterialTheme.typography.bodySmall,
        color     = MaterialTheme.customColors.textTertiary,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
        modifier  = Modifier.fillMaxWidth()
    )
}

@Composable
private fun WarningText(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(
        text  = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.customColors.error
    )
}

private fun formatSize(bytes: Int): String =
    if (bytes >= 1024 * 1024) String.format(Locale.US, "%.1fMB", bytes / 1024f / 1024f)
    else "${bytes / 1024}KB"

private fun formatDate(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

private fun formatEta(seconds: Int): String =
    if (seconds >= 60) "${seconds / 60}분 ${seconds % 60}초" else "${seconds}초"
