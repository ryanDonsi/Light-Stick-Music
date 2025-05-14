package com.dongsitech.lightstickmusicdemo.ui

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.view.MotionEvent
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.dongsitech.lightstickmusicdemo.viewmodel.LightStickViewModel
import kotlin.math.*
import androidx.core.graphics.get
import com.dongsitech.lightstickmusicdemo.R

@SuppressLint("ClickableViewAccessibility")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LightStickScreen(
    viewModel: LightStickViewModel,
    deviceName: String,
    deviceAddress: String,
    navController: NavController
) {
    val context = LocalContext.current
    val bitmap = remember {
        BitmapFactory.decodeResource(/* res = */ context.resources, /* id = */ R.drawable.pallet)
    }

    var selectedColor by remember { mutableStateOf(Color.Black) }
    var touchPosition by remember { mutableStateOf<Offset?>(null) }
    var imageSize by remember { mutableStateOf(IntSize.Zero) }

    val circleRadiusDp = 125.dp
    val touchIndicatorSizeDp = 20.dp

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = deviceName,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = deviceAddress,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.disconnectToDevice()
                        navController.popBackStack()
                    }) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBackIosNew,
                            contentDescription = "뒤로 가기"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(circleRadiusDp * 2)
                    .onGloballyPositioned { coords ->
                        imageSize = coords.size
                    }
            ) {
                AndroidView(
                    factory = { ctx ->
                        ImageView(ctx).apply {
                            setImageResource(R.drawable.pallet)
                            scaleType = ImageView.ScaleType.FIT_XY
                            setOnTouchListener { _, event ->
                                if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                                    val adjustedOffset = calculateAdjustedOffset(event.x, event.y, width.toFloat(), height.toFloat())
                                    val color = extractColorFromBitmap(bitmap, adjustedOffset.x, adjustedOffset.y, width, height)
                                    selectedColor = color
                                    touchPosition = adjustedOffset
                                    viewModel.sendLedColor(
                                        (color.red * 255).toInt(),
                                        (color.green * 255).toInt(),
                                        (color.blue * 255).toInt(),
                                        0
                                    )
                                }
                                true
                            }
                        }
                    },
                    modifier = Modifier.matchParentSize()
                )

                touchPosition?.let { offset ->
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (offset.x - touchIndicatorSizeDp.toPx() / 2).roundToInt(),
                                    (offset.y - touchIndicatorSizeDp.toPx() / 2).roundToInt()
                                )
                            }
                            .size(touchIndicatorSizeDp)
                            .border(2.dp, Color.White, shape = RoundedCornerShape(50))
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .border(1.dp, Color.Gray)
                        .background(selectedColor)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text("RGB: ${(selectedColor.red * 255).toInt()}, ${(selectedColor.green * 255).toInt()}, ${(selectedColor.blue * 255).toInt()}")
            }
        }
    }
}

private fun calculateAdjustedOffset(x: Float, y: Float, width: Float, height: Float): Offset {
    val centerX = width / 2
    val centerY = height / 2
    val dx = x - centerX
    val dy = y - centerY
    val distance = sqrt(dx * dx + dy * dy)
    val radius = (width / 2) - 1

    return if (distance > radius) {
        val ratio = radius / distance
        Offset(centerX + dx * ratio, centerY + dy * ratio)
    } else {
        Offset(x, y)
    }
}

private fun extractColorFromBitmap(bitmap: android.graphics.Bitmap, x: Float, y: Float, viewWidth: Int, viewHeight: Int): Color {
    val xRatio = x / viewWidth
    val yRatio = y / viewHeight
    val bmpX = (bitmap.width * xRatio).toInt().coerceIn(0, bitmap.width - 1)
    val bmpY = (bitmap.height * yRatio).toInt().coerceIn(0, bitmap.height - 1)
    val pixel = bitmap[bmpX, bmpY]
    return Color(android.graphics.Color.red(pixel), android.graphics.Color.green(pixel), android.graphics.Color.blue(pixel))
}