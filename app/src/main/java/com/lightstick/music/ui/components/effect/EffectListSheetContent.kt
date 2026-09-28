package com.lightstick.music.ui.components.effect

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightstick.music.R
import com.lightstick.music.ui.viewmodel.EffectViewModel

/** groupMask를 지정해 그룹 단위로 연출하는 EffectList 번호 (그룹 물결/쌓기/스캐너) */
private val GROUP_EFFECT_LIST_NUMBERS = setOf(4, 5, 6)

@Composable
fun EffectListSheetContent(
    effectLists: List<EffectViewModel.UiEffectType.EffectList>,
    selectedEffectListNumber: Int?,
    isGroupEffectAllowed: Boolean,
    onEffectClick: (Int) -> Unit,
    onClearClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .padding(bottom = 32.dp)
    ) {
        Text(
            text = "EFFECT를 선택하세요.",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        effectLists.forEach { effect ->
            val isSelected = selectedEffectListNumber == effect.number
            val isEnabled = isGroupEffectAllowed || effect.number !in GROUP_EFFECT_LIST_NUMBERS
            EffectListRow(
                effect = effect,
                isSelected = isSelected,
                isEnabled = isEnabled,
                onClick = { onEffectClick(effect.number) }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "설정 해제",
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp
            ),
            color = if (selectedEffectListNumber != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = selectedEffectListNumber != null) { onClearClick() }
                .padding(vertical = 16.dp)
        )
    }
}

@Composable
private fun EffectListRow(
    effect: EffectViewModel.UiEffectType.EffectList,
    isSelected: Boolean,
    isEnabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isEnabled, onClick = onClick)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = effect.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            if (isSelected) {
                Text(
                    text = "반복 재생 중",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            if (!isEnabled) {
                Text(
                    text = "BLE 모드 미지원",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        Icon(
            painter = painterResource(id = if (isSelected) R.drawable.ic_radio_s else R.drawable.ic_radio_n),
            contentDescription = "Selected",
            tint = if (!isEnabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                   else if (isSelected) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.size(24.dp)
        )
    }
}
