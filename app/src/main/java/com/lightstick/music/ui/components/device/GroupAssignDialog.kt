package com.lightstick.music.ui.components.device

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.lightstick.music.core.util.toComposeColor
import com.lightstick.music.ui.components.common.BaseDialog
import com.lightstick.music.ui.components.common.CustomSlider
import com.lightstick.music.ui.theme.customColors
import com.lightstick.types.GroupPalette

/**
 * 그룹 배정 방송 다이얼로그
 *
 * 이번 행사의 전체 그룹 개수(1~20)를 먼저 정하고, 그중 그룹 하나를 선택해 전송하면
 * 연결된 기기(RL 중계기 또는 직접연결된 GL/LS)가 해당 그룹 팔레트 색으로 BLINK
 * 방송(GroupSetup)을 시작한다. 방송을 받는 동안 응원봉에서 지정 버튼을 누르면
 * 그 응원봉이 선택한 그룹으로 저장된다.
 *
 * @param groupCount 이번 행사의 전체 그룹 개수 (1~[GroupPalette.MAX_GROUP_ID])
 * @param onGroupCountChange 그룹 개수 변경 시 호출 — 호출부에서 영속화(GroupPreferences)
 */
@Composable
fun GroupAssignDialog(
    groupCount: Int,
    onGroupCountChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (groupId: Int) -> Unit
) {
    var selectedGroupId by remember { mutableStateOf<Int?>(null) }

    // 그룹 개수가 줄어 현재 선택이 범위를 벗어나면 선택 해제
    LaunchedEffect(groupCount) {
        if ((selectedGroupId ?: 0) > groupCount) selectedGroupId = null
    }

    BaseDialog(
        title = "그룹 배정",
        subtitle = "그룹을 선택해 전송하면, 방송을 받는 동안\n버튼을 누른 응원봉이 해당 그룹으로 저장됩니다.",
        onDismiss = onDismiss,
        onConfirm = { selectedGroupId?.let(onConfirm) },
        confirmText = "전송",
        dismissText = "취소",
        confirmEnabled = selectedGroupId != null,
        scrollable = false
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "전체 그룹 수 (${groupCount}개)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                CustomSlider(
                    value = groupCount,
                    onValueChange = onGroupCountChange,
                    valueRange = GroupPalette.MIN_GROUP_ID..GroupPalette.MAX_GROUP_ID,
                    steps = GroupPalette.MAX_GROUP_ID - GroupPalette.MIN_GROUP_ID - 1,
                    enableHaptic = true,
                    trackColor = MaterialTheme.customColors.surfaceGlass,
                    thumbColor = MaterialTheme.customColors.onSurface,
                    trackHeight = 8.dp,
                    thumbSize = 24.dp
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(groupCount) { index ->
                    val groupId = index + 1
                    GroupSwatch(
                        groupId = groupId,
                        color = GroupPalette.colorFor(groupId).toComposeColor(),
                        isSelected = groupId == selectedGroupId,
                        onClick = { selectedGroupId = groupId }
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupSwatch(
    groupId: Int,
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .border(
                width = if (isSelected) 3.dp else 0.dp,
                color = if (isSelected) MaterialTheme.customColors.onSurface else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = groupId.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = if (color.luminance() > 0.5f) Color.Black else Color.White
        )
    }
}
