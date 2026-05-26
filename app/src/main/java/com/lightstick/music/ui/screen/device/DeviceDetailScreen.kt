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
import com.lightstick.music.ui.components.common.CommonProgressBar
import com.lightstick.music.ui.components.device.DeviceInfoHeader
import com.lightstick.music.ui.components.device.SettingLabel
import com.lightstick.music.ui.components.device.SettingToggleItem
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.theme.surfaceGlass

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
    isOtaInProgress: Boolean,
    otaProgress: Int,
    onBackClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onDeviceInfoClick: () -> Unit,
    onCallEventToggle: (Boolean) -> Unit,
    onSmsEventToggle: (Boolean) -> Unit,
    onBroadcastingToggle: (Boolean) -> Unit,
    onFindClick: () -> Unit,
    onOtaUpdateClick: () -> Unit,
    onAbortOta: () -> Unit,
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
                .padding(
                    top = paddingValues.calculateTopPadding(),
                    bottom = paddingValues.calculateBottomPadding()
                )
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 24.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.Top
        ) {

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
                    DeviceInfoHeader(
                        deviceName = deviceName,
                        batteryLevel = batteryLevel,
                        macAddress = macAddress,
                        rssi = rssi,
                        canShowAddress = true,
                        showBatteryBadge = true
                    )

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

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SettingLabel(
                                label = "OTA",
                                description = if (isOtaInProgress)
                                    "펌웨어 업데이트 중 ($otaProgress%)"
                                else
                                    "펌웨어 업데이트를 진행",
                                modifier = Modifier.weight(1f)
                            )
                            CustomChip(
                                text = if (isOtaInProgress) "OTA중지" else "업데이트",
                                onClick = if (isOtaInProgress) onAbortOta else onOtaUpdateClick,
                                containerColor = if (isOtaInProgress)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.customColors.primaryContainer,
                                contentColor = if (isOtaInProgress)
                                    MaterialTheme.colorScheme.onError
                                else
                                    MaterialTheme.customColors.onSurface
                            )
                        }
                        if (isOtaInProgress) {
                            CommonProgressBar(
                                progress = otaProgress / 100f,
                                modifier = Modifier.fillMaxWidth(),
                                height = 2.dp
                            )
                        }
                    }
                }
            }

        }
    }
}
