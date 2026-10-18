package com.easemic.app.feature.reader

import com.easemic.app.i18n.AppStrings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.easemic.app.i18n.tr

object SpreadPageFloatingBadge {
    const val TEST_TAG = "reader-spread-floating-badge"
    const val ROTATE_PILL_TEST_TAG = "reader-spread-rotate-pill"
    const val SPLIT_PILL_TEST_TAG = "reader-spread-split-pill"
}

/**
 * #E125 (Bilibili Manga `[横屏]观看大图` + Zaimanhua `ComicDualPageSplit`):
 * Compact floating badge shown when a wide double-page spread (`width / height >= 1.20f`)
 * is active, allowing one-tap switching between:
 * - Split half-page view (`Split 1/2 -> 2/2`, ordered `R -> L` for Japanese RTL manga or `L -> R` for LTR)
 * - Landscape 90° rotation (`Rotate 90°` / `[横屏]观看大图`)
 */
@Composable
fun BoxScope.SpreadPageFloatingBadge(
    subSlot: SpreadPageSubSlot,
    rtl: Boolean,
    onCycleSplit: () -> Unit,
    onToggleRotate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val splitOrder = ReaderPageZoom.spreadSplitOrder(rtl)
    val splitLabel = when (subSlot) {
        splitOrder[0] -> if (rtl) {
            tr("Split 1/2 (R)", "拆分 1/2（右）", "拆分 1/2（右）", "分割 1/2 (R)")
        } else {
            tr("Split 1/2 (L)", "拆分 1/2（左）", "拆分 1/2（左）", "分割 1/2 (L)")
        }
        splitOrder[1] -> if (rtl) {
            tr("Split 2/2 (L)", "拆分 2/2（左）", "拆分 2/2（左）", "分割 2/2 (L)")
        } else {
            tr("Split 2/2 (R)", "拆分 2/2（右）", "拆分 2/2（右）", "分割 2/2 (R)")
        }
        else -> if (rtl) {
            tr("Split R→L", "拆分 右→左", "拆分 右→左", "分割 R→L")
        } else {
            tr("Split L→R", "拆分 左→右", "拆分 左→右", "分割 L→R")
        }
    }
    val rotateActive = subSlot == SpreadPageSubSlot.ROTATED_90
    val splitActive = subSlot == SpreadPageSubSlot.LEFT_HALF || subSlot == SpreadPageSubSlot.RIGHT_HALF

    Row(
        modifier = modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 20.dp)
            .background(
                color = Color.Black.copy(alpha = 0.72f),
                shape = RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .testTag(SpreadPageFloatingBadge.TEST_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = if (splitActive) {
                Color(0xFF3B5998).copy(alpha = 0.90f)
            } else {
                Color.White.copy(alpha = 0.16f)
            },
            contentColor = Color.White,
            modifier = Modifier
                .testTag(SpreadPageFloatingBadge.SPLIT_PILL_TEST_TAG)
                .clickable(onClick = onCycleSplit)
                .semantics {
                    contentDescription = AppStrings.splitDoublePage(splitLabel)
                },
        ) {
            Text(
                text = splitLabel,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = if (rotateActive) {
                Color(0xFF2E6B4E).copy(alpha = 0.90f)
            } else {
                Color.White.copy(alpha = 0.16f)
            },
            contentColor = Color.White,
            modifier = Modifier
                .testTag(SpreadPageFloatingBadge.ROTATE_PILL_TEST_TAG)
                .clickable(onClick = onToggleRotate)
                .semantics {
                    contentDescription = if (rotateActive) {
                        tr("Exit rotated spread view", "退出旋转双页视图", "退出旋轉跨頁檢視", "回転見開き表示を終了")
                    } else {
                        tr("Rotate spread 90 degrees", "将双页旋转 90 度", "將跨頁旋轉 90 度", "見開きを 90 度回転")
                    }
                },
        ) {
            Text(
                text = if (rotateActive) {
                    tr("90° · Reset", "90° · 重置", "90° · 重設", "90° · リセット")
                } else {
                    tr("Rotate 90°", "旋转 90°", "旋轉 90°", "90°回転")
                },
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}
