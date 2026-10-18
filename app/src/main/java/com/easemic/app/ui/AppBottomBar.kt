package com.easemic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.easemic.app.i18n.AppStrings
import com.easemic.app.ui.library.lucideActivityTray
import com.easemic.app.ui.library.lucideBook
import com.easemic.app.ui.library.lucideClock
import com.easemic.app.ui.library.lucideCompass
import com.easemic.app.ui.library.lucideSliders
import com.easemic.app.ui.theme.QuietShelfColors

/** Primary destinations reachable from the bottom navigation bar. */
enum class AppBottomBarDestination {
    LIBRARY,
    BROWSE,
    ACTIVITY,
    HISTORY,
    MORE,
}

private const val NAV_TOUCH_TARGET_DP = 48

/**
 * Floating Quiet Shelf pill bottom bar (Library / Browse / Activity / History /
 * More). Matches the freeze `.bnav` inset rounded chrome, not a full-bleed
 * Material bar.
 */
@Composable
fun AppBottomBar(
    selected: AppBottomBarDestination,
    onSelect: (AppBottomBarDestination) -> Unit,
    libraryHasUnseenUpdates: Boolean = false,
) {
    val isDark = MaterialTheme.colorScheme.background == QuietShelfColors.Dark.surface
    val barColor = if (isDark) {
        QuietShelfColors.Dark.card.copy(alpha = 0.94f)
    } else {
        QuietShelfColors.Light.card.copy(alpha = 0.92f)
    }
    val borderColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color(0x141C1915)
    val muted = if (isDark) QuietShelfColors.Dark.muted else QuietShelfColors.Light.muted
    val selectedColor = if (isDark) QuietShelfColors.Dark.statusOk else QuietShelfColors.Light.statusOk
    val dotColor = if (isDark) QuietShelfColors.Dark.statusBad else QuietShelfColors.Light.statusBad

    val libraryIcon = remember { lucideBook(filled = false) }
    val browseIcon = remember { lucideCompass() }
    val activityIcon = remember { lucideActivityTray() }
    val historyIcon = remember { lucideClock() }
    val moreIcon = remember { lucideSliders() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(barColor)
                .border(1.dp, borderColor, RoundedCornerShape(18.dp))
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuietNavItem(
                keyName = "Library",
                label = AppStrings.navLibrary,
                icon = libraryIcon,
                selected = selected == AppBottomBarDestination.LIBRARY,
                selectedColor = selectedColor,
                mutedColor = muted,
                showUnseenDot = libraryHasUnseenUpdates,
                dotColor = dotColor,
                onClick = { onSelect(AppBottomBarDestination.LIBRARY) },
            )
            QuietNavItem(
                keyName = "Browse",
                label = AppStrings.navBrowse,
                icon = browseIcon,
                selected = selected == AppBottomBarDestination.BROWSE,
                selectedColor = selectedColor,
                mutedColor = muted,
                onClick = { onSelect(AppBottomBarDestination.BROWSE) },
            )
            QuietNavItem(
                keyName = "Activity",
                label = AppStrings.navActivity,
                icon = activityIcon,
                selected = selected == AppBottomBarDestination.ACTIVITY,
                selectedColor = selectedColor,
                mutedColor = muted,
                onClick = { onSelect(AppBottomBarDestination.ACTIVITY) },
            )
            QuietNavItem(
                keyName = "History",
                label = AppStrings.navHistory,
                icon = historyIcon,
                selected = selected == AppBottomBarDestination.HISTORY,
                selectedColor = selectedColor,
                mutedColor = muted,
                onClick = { onSelect(AppBottomBarDestination.HISTORY) },
            )
            QuietNavItem(
                keyName = "More",
                label = AppStrings.navMore,
                icon = moreIcon,
                selected = selected == AppBottomBarDestination.MORE,
                selectedColor = selectedColor,
                mutedColor = muted,
                onClick = { onSelect(AppBottomBarDestination.MORE) },
            )
        }
    }
}

@Composable
private fun QuietNavItem(
    keyName: String,
    label: String,
    icon: ImageVector,
    selected: Boolean,
    selectedColor: Color,
    mutedColor: Color,
    onClick: () -> Unit,
    showUnseenDot: Boolean = false,
    dotColor: Color = mutedColor,
) {
    val color = if (selected) selectedColor else mutedColor
    val pillBackground = if (selected) selectedColor.copy(alpha = 0.13f) else Color.Transparent
    Column(
        modifier = Modifier
            .heightIn(min = NAV_TOUCH_TARGET_DP.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
            )
            .semantics {
                set(
                    SemanticsProperties.ContentDescription,
                    if (showUnseenDot) {
                        listOf(AppStrings.navigateTo(label), AppStrings.libraryNewChapters)
                    } else {
                        listOf(AppStrings.navigateTo(label))
                    },
                )
                this.selected = selected
            }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(99.dp))
                .background(pillBackground)
                .padding(horizontal = 12.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier
                    .size(19.dp)
                    .testTag("bottom-bar-icon-${keyName.lowercase()}"),
            )
            if (showUnseenDot) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-1).dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = color,
        )
    }
}
