package com.lightstick.music.ui.components.device

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lightstick.LSBluetooth
import com.lightstick.device.CommandQueueStats
import com.lightstick.device.Device
import com.lightstick.music.data.local.preferences.BleTuningPreferences
import com.lightstick.music.ui.components.common.BaseButton
import com.lightstick.music.ui.components.common.BaseDialog
import com.lightstick.music.ui.components.common.ButtonStyle
import com.lightstick.music.ui.theme.customColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * BLE 전송 설정 다이얼로그 (디버그 빌드 전용 — 디바이스 설정 화면의 ⋮ 메뉴에서만 연다).
 *
 * SDK 의 GATT 명령 큐 값을 바꾸고, 연결된 모든 기기의 큐 상태(대기/피크/드롭/치환/실행)를 보면서 튜닝한다.
 * - 최소 전송 간격(0~1000ms, 기본 50): 한 명령이 끝난 뒤 다음 명령까지 최소한 기다리는 시간 (배수로 올림되지 않는 하한값)
 * - 큐 크기(1~256, 기본 8): 기기별 대기 명령 최대 개수. 초과하면 가장 오래된 대기 명령이 버려진다
 *
 * **두 값은 모든 기기에 공통**이며 즉시 적용된다(연결된 기기 포함). 기기별로 따로 정할 수 없다.
 * 값은 [BleTuningPreferences]에 저장되어 앱 시작 때 복원된다. 큐는 기기마다 따로 있고
 * FF02 이펙트뿐 아니라 그 기기의 모든 GATT 작업이 공유한다 — 아래 상태 목록은 기기별이다.
 */
@Composable
fun BleTuningDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    var intervalText by remember { mutableStateOf(LSBluetooth.getCommandIntervalMs().toString()) }
    var queueText by remember { mutableStateOf(LSBluetooth.getCommandQueueSize().toString()) }
    var status by remember {
        mutableStateOf("현재 적용: ${LSBluetooth.getCommandIntervalMs()}ms / 큐 ${LSBluetooth.getCommandQueueSize()}")
    }
    var deviceStats by remember { mutableStateOf<List<Pair<Device, CommandQueueStats?>>>(emptyList()) }

    val interval = intervalText.toLongOrNull()
    val queue = queueText.toIntOrNull()
    val intervalOk = interval != null && interval in 0L..LSBluetooth.MAX_COMMAND_INTERVAL_MS
    val queueOk = queue != null && queue in 1..LSBluetooth.MAX_COMMAND_QUEUE_SIZE

    // 다이얼로그가 떠 있는 동안 연결된 모든 기기의 큐 상태를 주기적으로 갱신한다
    LaunchedEffect(Unit) {
        while (isActive) {
            deviceStats = connectedDevices().map { it to it.getCommandQueueStats() }
            delay(500)
        }
    }

    fun applyValues(intervalMs: Long, queueSize: Int) {
        LSBluetooth.setCommandIntervalMs(intervalMs)
        LSBluetooth.setCommandQueueSize(queueSize)
        BleTuningPreferences.setCommandIntervalMs(context, intervalMs)
        BleTuningPreferences.setCommandQueueSize(context, queueSize)
        // SDK 에 실제로 적용된 값을 다시 읽어 보여 준다
        intervalText = LSBluetooth.getCommandIntervalMs().toString()
        queueText = LSBluetooth.getCommandQueueSize().toString()
        status = "적용됨: ${LSBluetooth.getCommandIntervalMs()}ms / 큐 ${LSBluetooth.getCommandQueueSize()}"
    }

    BaseDialog(
        title = "BLE 전송 설정",
        onDismiss = onDismiss,
        onConfirm = { applyValues(interval!!, queue!!) },
        confirmText = "적용",
        dismissText = "닫기",
        confirmEnabled = intervalOk && queueOk
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "아래 값은 연결된 모든 기기에 공통으로 적용됩니다(기기별 설정 불가). " +
                    "GATT 명령 큐는 기기마다 따로 있고, 이펙트뿐 아니라 읽기·게임·OTA 등 그 기기의 모든 GATT 작업이 공유합니다. " +
                    "값은 저장되어 다음 실행 때 복원됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.customColors.onSurfaceVariant
            )

            OutlinedTextField(
                value = intervalText,
                onValueChange = { intervalText = it.filter(Char::isDigit).take(4) },
                label = { Text("최소 전송 간격 (ms)") },
                supportingText = {
                    Text("0~${LSBluetooth.MAX_COMMAND_INTERVAL_MS}, 기본 ${LSBluetooth.DEFAULT_COMMAND_INTERVAL_MS} (0 = 제한 없음)")
                },
                isError = !intervalOk,
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = queueText,
                onValueChange = { queueText = it.filter(Char::isDigit).take(3) },
                label = { Text("큐 크기 (기기당 대기 명령 수)") },
                supportingText = {
                    Text("1~${LSBluetooth.MAX_COMMAND_QUEUE_SIZE}, 기본 ${LSBluetooth.DEFAULT_COMMAND_QUEUE_SIZE} (초과 시 오래된 명령 버려짐)")
                },
                isError = !queueOk,
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            if (interval != null && queue != null && intervalOk && queueOk) {
                Text(
                    text = "예상 최대 지연 ≈ 큐 × 최소 전송 간격 = ${queue * interval}ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.customColors.onSurface
                )
            }

            Text(
                text = status,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.customColors.primary
            )

            BaseButton(
                text = "기본값으로 초기화",
                onClick = {
                    applyValues(
                        LSBluetooth.DEFAULT_COMMAND_INTERVAL_MS,
                        LSBluetooth.DEFAULT_COMMAND_QUEUE_SIZE
                    )
                },
                style = ButtonStyle.SURFACE,
                modifier = Modifier.fillMaxWidth().height(44.dp)
            )

            QueueStatsSection(
                deviceStats = deviceStats,
                onResetStats = {
                    connectedDevices().forEach { it.resetCommandQueueStats() }
                    deviceStats = connectedDevices().map { it to it.getCommandQueueStats() }
                }
            )
        }
    }
}

/** 연결된 기기별 큐 상태. `dropped`가 0이 아니면 오버플로로 명령이 유실된 것이다 */
@Composable
private fun QueueStatsSection(
    deviceStats: List<Pair<Device, CommandQueueStats?>>,
    onResetStats: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "기기별 큐 상태",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.customColors.onSurface
        )

        if (deviceStats.isEmpty()) {
            Text(
                text = "연결된 기기가 없습니다",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.customColors.onSurfaceVariant
            )
            return
        }

        deviceStats.forEach { (device, stats) ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = device.name?.takeIf { it.isNotBlank() } ?: device.mac,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.customColors.onSurface
                )
                if (stats == null) {
                    Text(
                        text = "상태를 읽을 수 없습니다",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.customColors.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "대기 ${stats.pending} · 피크 ${stats.peakPending} · 실행 ${stats.executed} · 치환 ${stats.replaced}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.customColors.onSurfaceVariant
                    )
                    Text(
                        text = "드롭(유실) ${stats.dropped}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (stats.dropped > 0) MaterialTheme.customColors.error
                        else MaterialTheme.customColors.onSurfaceVariant
                    )
                }
            }
        }

        BaseButton(
            text = "통계 초기화",
            onClick = onResetStats,
            style = ButtonStyle.SURFACE,
            modifier = Modifier.fillMaxWidth().height(44.dp)
        )
    }
}

/** 연결된 기기 목록 (권한이 없거나 조회에 실패하면 빈 목록) */
@SuppressLint("MissingPermission")
private fun connectedDevices(): List<Device> =
    try {
        LSBluetooth.connectedDevices()
    } catch (_: Throwable) {
        emptyList()
    }
