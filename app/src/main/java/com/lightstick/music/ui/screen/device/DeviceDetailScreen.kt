package com.lightstick.music.ui.screen.device

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lightstick.music.data.model.DeviceDetailInfo
import com.lightstick.music.ui.components.common.BaseButton
import com.lightstick.music.ui.components.common.ButtonStyle
import com.lightstick.music.ui.components.common.CustomChip
import com.lightstick.music.ui.components.common.CustomTopBar
import com.lightstick.music.ui.components.device.DeviceInfoHeader
import com.lightstick.music.ui.components.device.SettingLabel
import com.lightstick.music.ui.components.device.SettingToggleItem
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.theme.surfaceGlass

/**
 * 디바이스 상세 정보 화면 (하나의 카드로 통합)
 *
 * ## 화면 구성:
 * 1. TopBar (뒤로가기 + "연결된 기기")
 * 2. 하나의 큰 카드:
 *    - DeviceInfoHeader (이름, MAC, RSSI, 배터리)
 *    - 연결 해제 버튼
 *    - Divider
 *    - 디바이스 정보 라벨
 *    - Divider
 *    - CALL Event 토글
 *    - Divider
 *    - SMS Event 토글
 *    - Divider
 *    - Broadcasting Mode 토글
 *    - Divider
 *    - FIND 라벨
 *    - Divider
 *    - OTA 섹션 (라벨 + 업데이트 버튼)
 *
 * @param deviceName 디바이스 이름
 * @param macAddress MAC 주소
 * @param rssi RSSI 값
 * @param batteryLevel 배터리 레벨 (nullable)
 * @param deviceInfo 디바이스 정보 (nullable)
 * @param callEventEnabled CALL Event 활성화 여부
 * @param smsEventEnabled SMS Event 활성화 여부
 * @param broadcastingEnabled Broadcasting 활성화 여부
 * @param onBackClick 뒤로가기 클릭
 * @param onDisconnectClick 연결 해제 클릭
 * @param onDeviceInfoClick 디바이스 정보 클릭
 * @param onCallEventToggle CALL Event 토글
 * @param onSmsEventToggle SMS Event 토글
 * @param onBroadcastingToggle Broadcasting 토글
 * @param onFindClick FIND 버튼 클릭
 * @param onOtaUpdateClick OTA 업데이트 버튼 클릭
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(
    deviceName: String,
    macAddress: String,
    rssi: Int,
    batteryLevel: Int?,
    deviceInfo: DeviceDetailInfo?,
    callEventEnabled: Boolean,
    smsEventEnabled: Boolean,
    broadcastingEnabled: Boolean,
    onBackClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onDeviceInfoClick: () -> Unit,
    onCallEventToggle: (Boolean) -> Unit,
    onSmsEventToggle: (Boolean) -> Unit,
    onBroadcastingToggle: (Boolean) -> Unit,
    onFindClick: () -> Unit,
    onOtaUpdateClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            CustomTopBar(
                title = "연결된 기기",
                showBackButton = true,
                onBackClick = onBackClick
            )
        },
        containerColor = MaterialTheme.customColors.background
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Top
        ) {

            // ===== 하나의 큰 카드로 통합 =====
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.customColors.surfaceGlass
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 24.dp,
                            top = 12.dp,
                            end = 20.dp,
                            bottom = 12.dp
                        ),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. 디바이스 정보 헤더
                    DeviceInfoHeader(
                        deviceName = deviceName,
                        batteryLevel = batteryLevel,
                        macAddress = macAddress,
                        rssi = rssi,
                        canShowAddress = true,
                        showBatteryBadge = true
                    )

                    // 2. 연결 해제 버튼
                    BaseButton(
                        text = "해제",
                        onClick = onDisconnectClick,
                        style = ButtonStyle.ERROR,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    )

                    HorizontalDivider(
                        modifier = Modifier.fillMaxWidth(),
                        thickness = 1.dp,
                        color = MaterialTheme.customColors.divider
                    )

                    // 3. 디바이스 정보
                    SettingLabel(
                        label = "디바이스 정보",
                        description = "기기의 정보를 확인",
                        onClick = onDeviceInfoClick
                    )

                    HorizontalDivider(
                        modifier = Modifier.fillMaxWidth(),
                        thickness = 1.dp,
                        color = MaterialTheme.customColors.divider
                    )

                    // 4. CALL Event
                    SettingToggleItem(
                        label = "CALL Event",
                        description = "전화벨이 울릴 때 특정 이팩트를 전달",
                        checked = callEventEnabled,
                        onCheckedChange = onCallEventToggle
                    )

                    HorizontalDivider(
                        modifier = Modifier.fillMaxWidth(),
                        thickness = 1.dp,
                        color = MaterialTheme.customColors.divider
                    )

                    // 5. SMS Event
                    SettingToggleItem(
                        label = "SMS Event",
                        description = "SMS 수신 시 특정 이팩트를 전달",
                        checked = smsEventEnabled,
                        onCheckedChange = onSmsEventToggle
                    )

                    HorizontalDivider(
                        modifier = Modifier.fillMaxWidth(),
                        thickness = 1.dp,
                        color = MaterialTheme.customColors.divider
                    )

                    // 6. Broadcasting Mode
                    SettingToggleItem(
                        label = "Broadcasting Mode",
                        description = "연결된 주변 응원봉에 신호를 전파해 함께 동작",
                        checked = broadcastingEnabled,
                        onCheckedChange = onBroadcastingToggle
                    )

                    HorizontalDivider(
                        modifier = Modifier.fillMaxWidth(),
                        thickness = 1.dp,
                        color = MaterialTheme.customColors.divider
                    )

                    // 7. FIND
                    SettingLabel(
                        label = "FIND",
                        description = "연결한 기기에 특정 이펙트를 전달하여 기기 찾기",
                        onClick = onFindClick
                    )

                    HorizontalDivider(
                        modifier = Modifier.fillMaxWidth(),
                        thickness = 1.dp,
                        color = MaterialTheme.customColors.divider
                    )

                    // 8. OTA
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingLabel(
                            label = "OTA",
                            description = "펌웨어 업데이트를 진행"
                        )

                        CustomChip(
                            text = "업데이트",
                            onClick = onOtaUpdateClick
                        )
                    }
                }
            }

//            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}