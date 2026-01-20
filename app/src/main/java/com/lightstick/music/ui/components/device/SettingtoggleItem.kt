package com.lightstick.music.ui.components.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lightstick.music.ui.components.common.CustomSwitch

/**
 * 설정 토글 아이템 컴포넌트 (Figma 디자인 적용)
 *
 * 디바이스 설정 화면에서 사용하는 토글 스위치 항목
 * - 좌측: 라벨 + 설명(회색)
 * - 우측: 토글 스위치 (Primary 색상)
 *
 * @param label 설정 항목 라벨 (예: "CALL Event")
 * @param description 설명 텍스트 (회색, nullable)
 * @param checked 토글 상태
 * @param onCheckedChange 토글 변경 콜백
 */
@Composable
fun SettingToggleItem(
    label: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingLabel(
            label = label,
            description = description,
            modifier = Modifier.weight(1f)
        )

        CustomSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}