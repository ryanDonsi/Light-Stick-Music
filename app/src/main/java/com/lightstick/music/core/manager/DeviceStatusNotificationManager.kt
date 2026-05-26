package com.lightstick.music.core.manager

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.lightstick.LSBluetooth
import com.lightstick.device.ConnectionState
import com.lightstick.music.R
import com.lightstick.music.core.permission.PermissionManager
import com.lightstick.music.core.util.Log
import com.lightstick.music.ui.activity.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 연결된 BLE 디바이스의 상태를 상태바 알림으로 표시합니다.
 * 디바이스 연결 시 표시, 모든 연결 해제 시 자동으로 알림을 제거합니다.
 */
object DeviceStatusNotificationManager {

    private const val TAG = "DeviceStatusNotif"
    const val CHANNEL_ID = "device_status_channel"
    const val NOTIFICATION_ID = 102

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val isFlowStarted = AtomicBoolean(false)

    // DeviceViewModel에서 readBattery() 결과를 캐시 → refreshNotification() 에서 재사용
    private val batteryCache = ConcurrentHashMap<String, Int>()

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        createChannel(context)
        val appContext = context.applicationContext
        cachedAppContext = appContext

        // onListenerConnected() 재호출(서비스 리바인드) 시에도 알림을 즉시 새로 고침
        scope.launch { refreshNotification(appContext) }

        // Flow 구독은 최초 1회만 (중복 collect 방지)
        if (!isFlowStarted.compareAndSet(false, true)) return

        scope.launch {
            combine(
                LSBluetooth.observeConnectionStates(),
                LSBluetooth.observeDeviceStates()
            ) { connStates, deviceStates ->
                Pair(connStates, deviceStates)
            }.collect { (connStates, deviceStates) ->
                val connectedMacs = connStates
                    .filter { it.value is ConnectionState.Connected }
                    .keys

                if (connectedMacs.isEmpty()) {
                    // flow 초기 빈 상태 vs 실제 연결 해제 구분:
                    // connectedCount()로 실제 상태 재확인 후에만 알림 제거
                    val actuallyConnected = try {
                        PermissionManager.hasBluetoothConnectPermission(appContext) &&
                            LSBluetooth.connectedCount() > 0
                    } catch (_: Exception) { false }
                    if (!actuallyConnected) cancelNotification(appContext)
                    return@collect
                }

                val deviceInfoList = connectedMacs.map { mac ->
                    val info = deviceStates[mac]?.deviceInfo
                    DeviceNotifInfo(
                        name = info?.deviceName?.takeIf { it.isNotBlank() }
                            ?: info?.modelName?.takeIf { it.isNotBlank() }
                            ?: mac,
                        battery = info?.batteryLevel ?: batteryCache[mac]
                    )
                }
                showNotification(appContext, deviceInfoList)
            }
        }
    }

    /**
     * 알림이 현재 표시되어 있지 않으면 재표시.
     * 사용자가 알림을 지웠을 때 주기적 재연결 루틴에서 호출.
     */
    fun ensureNotificationVisible(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val isShowing = nm.activeNotifications.any { it.id == NOTIFICATION_ID }
        if (!isShowing) {
            Log.d(TAG, "알림 없음 감지 → 재표시")
            scope.launch { refreshNotification(context.applicationContext) }
        }
    }

    /**
     * DeviceViewModel 배터리 조회 결과를 캐시 → 알림 즉시 업데이트.
     */
    fun updateDeviceBattery(mac: String, level: Int) {
        batteryCache[mac] = level
        val ctx = cachedAppContext ?: return
        scope.launch { refreshNotification(ctx) }
    }

    // updateDeviceBattery()에서 사용하기 위해 appContext를 start() 시점에 저장
    private var cachedAppContext: Context? = null

    @SuppressLint("MissingPermission")
    private suspend fun refreshNotification(appContext: Context) {
        if (!PermissionManager.hasBluetoothConnectPermission(appContext)) return
        try {
            val connected = LSBluetooth.connectedDevices()
            if (connected.isEmpty()) return
            val deviceStates = withTimeoutOrNull(300L) {
                LSBluetooth.observeDeviceStates().first()
            }
            showNotification(appContext, connected.map { device ->
                val info = deviceStates?.get(device.mac)?.deviceInfo
                DeviceNotifInfo(
                    name = info?.deviceName?.takeIf { it.isNotBlank() }
                        ?: info?.modelName?.takeIf { it.isNotBlank() }
                        ?: device.name ?: device.mac,
                    battery = info?.batteryLevel ?: batteryCache[device.mac]
                )
            })
        } catch (e: Exception) {
            Log.w(TAG, "알림 새로 고침 실패: ${e.message}")
        }
    }

    private fun showNotification(context: Context, devices: List<DeviceNotifInfo>) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // "DeviceName 연결됨" / "Dev1, Dev2 연결됨"
        val title = devices.joinToString(", ") { it.name } + " 연결됨"

        // "🔋85%" or "Dev1 🔋85% · Dev2 🔋70%" (여러 기기일 때 이름 포함)
        val contentText = if (devices.size == 1) {
            devices.first().battery?.let { "🔋$it%" } ?: "배터리 정보 없음"
        } else {
            devices.joinToString(" · ") { d ->
                d.battery?.let { "${d.name} 🔋$it%" } ?: d.name
            }
        }

        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentTitle(title)
            .setContentText(contentText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
        Log.d(TAG, "알림 업데이트: $title | $contentText")
    }

    private fun cancelNotification(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NOTIFICATION_ID)
        Log.d(TAG, "알림 제거 (연결된 디바이스 없음)")
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "연결된 기기 상태",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "연결된 라이트스틱 기기의 이름과 배터리 상태를 표시합니다"
            setShowBadge(false)
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private data class DeviceNotifInfo(val name: String, val battery: Int?)
}
