package com.lightstick.music.ui.screen.device

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lightstick.music.data.model.DeviceDetailInfo
import com.lightstick.music.ui.components.common.BaseButton
import com.lightstick.music.ui.components.common.ButtonStyle
import com.lightstick.music.ui.components.common.CustomTopBar
import com.lightstick.music.ui.components.device.DeviceInfoCard
import com.lightstick.music.ui.components.device.SettingLabel
import com.lightstick.music.ui.components.device.SettingToggleItem
import com.lightstick.music.ui.theme.customColors

/**
 * 디바이스 상세 정보 화면 (Figma 디자인 적용)
 *
 * ## 화면 구성:
 * 1. TopBar (뒤로가기 + "연결된 기기")
 * 2. DeviceInfoCard (이름, MAC, RSSI, 배터리)
 * 3. 연결 해제 버튼 (빨간색)
 * 4. 디바이스 설정 섹션
 *    - CALL Event 토글
 *    - SMS Event 토글
 *    - Broadcasting Mode 토글
 * 5. FIND 버튼
 * 6. OTA 섹션
 *    - 설명 텍스트
 *    - 업데이트 버튼
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
 * @param onDeviceInfoClick 디바이스 정보 클릭 (디바이스 정보 팝업)
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
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ===== 1. 디바이스 정보 카드 =====
            DeviceInfoCard(
                deviceName = deviceName,
                batteryLevel = batteryLevel,
                macAddress = macAddress,
                rssi = rssi
            )

            // ===== 2. 연결 해제 버튼 =====
            BaseButton(
                text = "연결 해제",
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

            // ===== 3. 디바이스 설정 섹션 =====
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SettingLabel(
                        label = "OTA",
                        description = "펌웨어 업데이트를 진행"
                    )

                    BaseButton(
                        text = "업데이트",
                        onClick = onOtaUpdateClick,
                        style = ButtonStyle.PRIMARY,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    )
                }
            }
        }
    }
}