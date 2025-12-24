package com.dongsitech.lightstickmusicdemo.ui.components.effect

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dongsitech.lightstickmusicdemo.viewmodel.EffectViewModel

/**
 * Custom Effect 생성 다이얼로그
 *
 * @param onDismiss 다이얼로그 닫기 콜백
 * @param onSave Custom Effect 저장 콜백 (name, baseType)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomEffectDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, baseType: EffectViewModel.BaseEffectType) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedBaseType by remember { mutableStateOf(EffectViewModel.BaseEffectType.ON) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom Effect 추가") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Effect 이름 입력
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Effect 이름") },
                    placeholder = { Text("예: My Custom Effect") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Base Effect Type 선택
                Text(
                    text = "Base Effect Type",
                    style = MaterialTheme.typography.titleSmall
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    EffectViewModel.BaseEffectType.values().forEach { baseType ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedBaseType == baseType,
                                    onClick = { selectedBaseType = baseType }
                                )
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedBaseType == baseType,
                                onClick = { selectedBaseType = baseType }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = baseType.displayName,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = getBaseTypeDescription(baseType),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(name.trim(), selectedBaseType)
                    }
                },
                enabled = name.isNotBlank()
            ) {
                Text("추가")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}

/**
 * Base Effect Type 설명 반환
 */
private fun getBaseTypeDescription(baseType: EffectViewModel.BaseEffectType): String {
    return when (baseType) {
        EffectViewModel.BaseEffectType.ON -> "LED를 켭니다"
        EffectViewModel.BaseEffectType.OFF -> "LED를 끕니다"
        EffectViewModel.BaseEffectType.STROBE -> "빠르게 깜빡입니다"
        EffectViewModel.BaseEffectType.BLINK -> "천천히 깜빡입니다"
        EffectViewModel.BaseEffectType.BREATH -> "숨쉬듯이 밝기가 변합니다"
    }
}