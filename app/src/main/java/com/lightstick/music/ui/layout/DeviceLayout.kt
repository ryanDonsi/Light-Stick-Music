package com.lightstick.music.ui.layout

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/** 태블릿 레이아웃을 쓰는 최소 창 너비(dp). Material "Expanded" 구간(840dp 이상)과 같다. */
private const val TABLET_MIN_WIDTH_DP = 840

/** 태블릿으로 취급하는 최소 짧은 변(dp). 이 이상이면 가로 화면 고정. */
private const val TABLET_MIN_SMALLEST_WIDTH_DP = 600

/**
 * 태블릿 가로 레이아웃을 쓸지 여부.
 * 기기 종류가 아니라 "현재 창 크기"로 판단한다 — 분할 화면처럼 창이 좁아지면 자동으로 폰 레이아웃으로 돌아간다.
 */
@Composable
fun isTabletLandscape(): Boolean {
    val c = LocalConfiguration.current
    return c.screenWidthDp >= TABLET_MIN_WIDTH_DP && c.screenWidthDp > c.screenHeightDp
}

/**
 * 기기 크기에 맞춰 화면 방향을 정한다: 태블릿(짧은 변 600dp 이상)은 가로, 폰은 세로 고정.
 * 매니페스트에서 세로로 고정하면 태블릿에서도 세로로 열리므로 코드에서 지정한다.
 */
fun Activity.applyDeviceOrientation() {
    requestedOrientation =
        if (resources.configuration.smallestScreenWidthDp >= TABLET_MIN_SMALLEST_WIDTH_DP) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
}
