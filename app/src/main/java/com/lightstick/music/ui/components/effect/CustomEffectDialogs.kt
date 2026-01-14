package com.lightstick.music.ui.components.effect

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lightstick.music.ui.components.common.BaseDialog
import com.lightstick.music.ui.components.common.ButtonStyle
import com.lightstick.music.ui.viewmodel.EffectViewModel

/**
 * 커스텀 이펙트 추가 버튼
 */
@Composable
fun AddEffectButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(84.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        ),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add Effect",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

/**
 * ✅ 수정: BaseDialog를 사용하도록 리팩토링
 * 커스텀 이펙트의 이름과 기반 타입을 지정하는 다이얼로그
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCustomEffectDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, baseType: EffectViewModel.UiEffectType.BaseEffectType) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val baseTypes = remember { EffectViewModel.UiEffectType.BaseEffectType.values() }
    var selectedBaseType by remember { mutableStateOf(baseTypes.first()) }
    var isDropdownExpanded by remember { mutableStateOf(false) }

    BaseDialog(
        title = "이펙트 이름",
        onDismiss = onDismiss,
        onConfirm = { onConfirm(name, selectedBaseType) },
        confirmEnabled = name.isNotBlank(),
        scrollable = false
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                singleLine = true,
                placeholder = { Text("STROBE02") }
            )

            // 기반 이펙트 선택 Dropdown
            ExposedDropdownMenuBox(
                expanded = isDropdownExpanded,
                onExpandedChange = { isDropdownExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedBaseType.displayName,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isDropdownExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = isDropdownExpanded,
                    onDismissRequest = { isDropdownExpanded = false }
                ) {
                    baseTypes.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(text = type.displayName) },
                            onClick = {
                                selectedBaseType = type
                                isDropdownExpanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}


/**
 * ✅ 수정: BaseDialog를 사용하도록 리팩토링
 * 커스텀 이펙트 추가를 최종 확인하는 다이얼로그
 */
@Composable
fun ConfirmAddEffectDialog(
    effectName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    BaseDialog(
        title = "\"${effectName}\" 이펙트를 추가할까요?",
        subtitle = null,
        onDismiss = onDismiss,
        onConfirm = onConfirm
    ) {
        Text(
            text = "최대 7개까지 추가가 가능합니다.\\n이펙트 상세 설정은 카드 추가 후 가능합니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

/**
 * ✅ 추가: 커스텀 이펙트 이름 변경 다이얼로그
 */
@Composable
fun RenameEffectDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (newName: String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }

    BaseDialog(
        title = "이름 변경",
        onDismiss = onDismiss,
        onConfirm = { onConfirm(name) },
        confirmEnabled = name.isNotBlank() && name != initialName,
        scrollable = false
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            singleLine = true
        )
    }
}


/**
 * ✅ 추가: 커스텀 이펙트 삭제 확인 다이얼로그
 */
@Composable
fun ConfirmDeleteEffectDialog(
    effectName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    BaseDialog(
        title = "'$effectName' 이펙트를 삭제할까요?",
        subtitle = "삭제된 이펙트는 복구할 수 없습니다.",
        onDismiss = onDismiss,
        onConfirm = onConfirm
    ) {
        // Content is handled by title and subtitle
    }
}