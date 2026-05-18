package com.lightstick.music.ui.screen.device

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.lightstick.music.data.local.storage.EffectPathPreferences
import com.lightstick.music.data.model.DeviceDetailInfo
import com.lightstick.music.ui.components.common.BaseButton
import com.lightstick.music.ui.components.common.BaseDialog
import com.lightstick.music.ui.components.common.ButtonStyle
import com.lightstick.music.ui.components.common.CustomChip
import com.lightstick.music.ui.components.common.CustomTopBar
import com.lightstick.music.ui.components.common.CommonProgressBar
import com.lightstick.music.ui.components.device.DeviceInfoHeader
import com.lightstick.music.ui.components.device.SettingLabel
import com.lightstick.music.ui.components.device.SettingToggleItem
import com.lightstick.music.ui.theme.customColors
import com.lightstick.music.ui.theme.customTextStyles
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
    onRequestEffectsDirectory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showEffectFolderDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CustomTopBar(
                title = "연결된 기기",
                showBackButton = true,
                onBackClick = onBackClick,
                actionContent = {
                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "더보기",
                                tint = MaterialTheme.customColors.onSurface
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            containerColor = MaterialTheme.customColors.surface
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "이펙트 파일 폴더 설정",
                                        style = MaterialTheme.customTextStyles.topBarSmall,
                                        color = MaterialTheme.customColors.onSurface
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    showEffectFolderDialog = true
                                }
                            )
                        }
                    }
                }
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

    if (showEffectFolderDialog) {
        val dirUri = EffectPathPreferences.getSavedDirectoryUri(context)
        val dirName = dirUri?.let { DocumentFile.fromTreeUri(context, it)?.name } ?: "미설정"

        BaseDialog(
            title = "이펙트 파일 폴더",
            onDismiss = { showEffectFolderDialog = false },
            onConfirm = {
                showEffectFolderDialog = false
                onRequestEffectsDirectory()
            },
            confirmText = "변경",
            dismissText = "닫기",
            scrollable = false
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.customColors.surfaceGlass,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "현재 폴더",
                        style = MaterialTheme.customTextStyles.topBarSmall,
                        color = MaterialTheme.customColors.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        text = dirName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.customColors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
