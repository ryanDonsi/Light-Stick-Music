package com.lightstick.music.presentation.components.effect

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Effect 이름 변경 다이얼로그
 *
 * ✅ Custom Effect의 이름을 변경할 수 있습니다.
 *
 * @param currentName 현재 이름
 * @param onNameChange 이름 변경 시 콜백
 * @param onDismiss 닫기 시 콜백
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EffectNameEditDialog(
    currentName: String,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("이펙트 이름 변경") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "이펙트의 이름을 입력하세요",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("이름") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        onNameChange(name.trim())
                        onDismiss()
                    }
                },
                enabled = name.isNotBlank()
            ) {
                Text("확인")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}