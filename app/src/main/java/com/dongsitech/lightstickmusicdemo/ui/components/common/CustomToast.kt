package com.dongsitech.lightstickmusicdemo.ui.components.common

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dongsitech.lightstickmusicdemo.R
import kotlinx.coroutines.delay

/**
 * 🎨 Custom Toast (체크 아이콘 포함)
 *
 * @param message 표시할 메시지
 * @param isVisible 토스트 표시 여부
 * @param onDismiss 토스트 사라질 때 콜백
 */
@Composable
fun CustomToast(
    message: String,
    isVisible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(isVisible) {
        if (isVisible) {
            delay(2000)  // 2초 후 자동 사라짐
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2C2C2E))  // 어두운 배경
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // ✅ 체크 아이콘
                Icon(
                    painter = painterResource(id = R.drawable.ic_check),
                    contentDescription = "Success",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                // ✅ 메시지
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    fontSize = 14.sp
                )
            }
        }
    }
}

/**
 * 토스트 상태 관리를 위한 State Holder
 */
@Composable
fun rememberToastState(): ToastState {
    return remember { ToastState() }
}

class ToastState {
    var isVisible by mutableStateOf(false)
        private set

    var message by mutableStateOf("")
        private set

    fun show(msg: String) {
        message = msg
        isVisible = true
    }

    fun dismiss() {
        isVisible = false
    }
}