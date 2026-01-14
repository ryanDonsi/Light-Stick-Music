package com.lightstick.music.ui.components.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightstick.music.R
import com.lightstick.music.ui.theme.customColors

@Composable
fun CustomNavigationBar(
    selectedRoute: String,
    onNavigate: (String) -> Unit,
    connectedDeviceCount: Int = 0,
    modifier: Modifier = Modifier
) {
    val navItems = listOf(
        NavItem("effect", R.drawable.ic_navi_effect),
        NavItem("music", R.drawable.ic_navi_music),
        NavItem(
            route = "deviceList",
            iconRes = R.drawable.ic_navi_device,
            badgeCount = connectedDeviceCount
        )
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(62.dp)              // ✅ 높이는 여기서
            .navigationBarsPadding(),   // ✅ padding도 여기서
        color = MaterialTheme.customColors.surface,
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            navItems.forEach { item ->
                NavBarItem(
                    item = item,
                    selected = selectedRoute.startsWith(item.route),
                    onClick = { onNavigate(item.route) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun NavBarItem(
    item: NavItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .width(109.dp)
            .height(58.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (item.badgeCount > 0) {
            BadgedBox(
                badge = {
                    Badge {
                        Text(
                            text = item.badgeCount.toString(),
                            fontSize = 10.sp
                        )
                    }
                }
            ) {
                NavIcon(item.iconRes, selected)
            }
        } else {
            NavIcon(item.iconRes, selected)
        }
    }
}

@Composable
private fun NavIcon(
    @DrawableRes iconRes: Int,
    selected: Boolean
) {
    Box(
        modifier = Modifier
            .width(40.dp)
            .height(50.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (selected)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

data class NavItem(
    val route: String,
    @DrawableRes val iconRes: Int,
    val badgeCount: Int = 0
)
