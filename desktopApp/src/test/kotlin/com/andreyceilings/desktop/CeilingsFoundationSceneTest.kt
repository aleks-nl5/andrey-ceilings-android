package com.andreyceilings.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.andreyceilings.shared.theme.CeilingsEdge
import com.andreyceilings.shared.theme.CeilingsEdgeGradient
import com.andreyceilings.shared.theme.CeilingsTheme
import com.andreyceilings.shared.ui.CeilingsFab
import com.andreyceilings.shared.ui.CeilingsFabProfile
import com.andreyceilings.shared.ui.CeilingsPinnedContent
import com.andreyceilings.shared.ui.CeilingsScreenScaffold
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real headless Compose layout/input/rendering using the existing Desktop runtime. */
class CeilingsFoundationSceneTest {
    @Test
    fun firstFrameAndResizeUseMeasuredPinnedHeightForShortAndLongLists() {
        for (count in listOf(1, 80)) {
            val pinnedHeight = mutableStateOf(97.dp)
            var firstTop = -1f
            var viewportHeight = 0
            val listState = LazyListState()
            val scene = ImageComposeScene(360, 640, Density(1f)) {
                CeilingsTheme(false) {
                    CeilingsScreenScaffold(
                        windowInsets = WindowInsets(0, 24, 0, 30),
                        contentPadding = PaddingValues(16.dp),
                        header = { Spacer(Modifier.height(56.dp)) },
                        pinned = { Spacer(Modifier.height(pinnedHeight.value)) },
                        bottomNavigation = { Spacer(Modifier.height(64.dp)) }
                    ) { padding ->
                        LazyColumn(Modifier.fillMaxSize().onGloballyPositioned {
                            viewportHeight = it.size.height
                        }, state = listState, contentPadding = padding) {
                            items(count) { index ->
                                Box(Modifier.fillMaxWidth().height(48.dp).onGloballyPositioned {
                                    if (index == 0) firstTop = it.positionInRoot().y
                                })
                            }
                        }
                    }
                }
            }
            try {
                scene.render().close()
                assertEquals(193f, firstTop, "First frame must reserve the actual pinned size")
                assertEquals(640, viewportHeight, "List draws under all foreground layers")
                assertEquals(110, listState.layoutInfo.afterContentPadding)
                pinnedHeight.value = 112.dp
                Snapshot.sendApplyNotifications()
                scene.constraints = Constraints.fixed(280, 480)
                scene.render(16_000_000).close()
                assertEquals(208f, firstTop)
                assertEquals(480, viewportHeight)
            } finally { scene.close() }
        }
    }

    @Test
    fun restoredScrollSurvivesResizeAndContentGesturesRemainAvailable() {
        // Same public list state restored by the screen's existing Saver.
        val state = LazyListState(firstVisibleItemIndex = 12, firstVisibleItemScrollOffset = 7)
        val scene = ImageComposeScene(360, 640) {
            CeilingsTheme(false) {
                CeilingsScreenScaffold(windowInsets = WindowInsets(0, 0, 0, 0),
                    pinned = { Spacer(Modifier.height(56.dp)) }) { padding ->
                    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = padding) {
                        items(80) { Box(Modifier.fillMaxWidth().height(48.dp)) }
                    }
                }
            }
        }
        try {
            scene.render().close()
            assertEquals(12, state.firstVisibleItemIndex)
            assertEquals(7, state.firstVisibleItemScrollOffset)
            scene.constraints = Constraints.fixed(280, 480)
            scene.render(16_000_000).close()
            assertEquals(12, state.firstVisibleItemIndex)
            assertEquals(7, state.firstVisibleItemScrollOffset)
            val before = state.firstVisibleItemIndex * 48 + state.firstVisibleItemScrollOffset
            scene.sendPointerEvent(PointerEventType.Press, Offset(140f, 300f),
                timeMillis = 1000, type = PointerType.Touch)
            scene.sendPointerEvent(PointerEventType.Move, Offset(140f, 200f),
                timeMillis = 1050, type = PointerType.Touch)
            scene.render(66_000_000).close()
            val forward = state.firstVisibleItemIndex * 48 + state.firstVisibleItemScrollOffset
            assertTrue(forward > before, "Pinned foreground must not block content scrolling")
            scene.sendPointerEvent(PointerEventType.Move, Offset(140f, 330f),
                timeMillis = 1100, type = PointerType.Touch)
            scene.render(116_000_000).close()
            val reversed = state.firstVisibleItemIndex * 48 + state.firstVisibleItemScrollOffset
            assertTrue(reversed < forward)
            scene.sendPointerEvent(PointerEventType.Release, Offset(140f, 330f),
                timeMillis = 1150, type = PointerType.Touch)
            // Drive the release/fling frames without a wall-clock sleep.
            repeat(8) { scene.render(166_000_000L + it * 50_000_000L).close() }
        } finally { scene.close() }
    }

    @Test
    fun imeAndSafeAreaAreUnionedAndInsetsAreConsumedForNestedScreens() {
        var bottom = 0.dp
        var nestedTop = (-1).dp
        val safe = WindowInsets(0, 24, 0, 30)
        val ime = WindowInsets(0, 0, 0, 240)
        val scene = ImageComposeScene(360, 640) {
            CeilingsTheme(false) {
                CeilingsScreenScaffold(
                    windowInsets = safe.union(ime), contentPadding = PaddingValues(16.dp),
                    bottomNavigation = { Spacer(Modifier.height(64.dp)) }
                ) { padding ->
                    bottom = padding.calculateBottomPadding()
                    // Host already reserved the insets; nested foundation explicitly opts out.
                    CeilingsScreenScaffold(windowInsets = WindowInsets(0, 0, 0, 0)) { nested ->
                        nestedTop = nested.calculateTopPadding()
                    }
                }
            }
        }
        try {
            scene.render().close()
            assertEquals(320.dp, bottom)
            assertEquals(0.dp, nestedTop)
        } finally { scene.close() }
    }

    @Test
    fun pinnedShieldProtectsOnlyOccupiedBoundsAndForegroundButtonsStayClickable() {
        var backgroundClicks = 0
        var actionClicks = 0
        val intercept = mutableStateOf(true)
        val scene = ImageComposeScene(320, 300) {
            CeilingsTheme(false) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().clickable { backgroundClicks++ })
                    CeilingsEdgeGradient(CeilingsEdge.TOP, Modifier.fillMaxWidth().height(180.dp))
                    CeilingsPinnedContent(Modifier.offset(y = 60.dp).width(160.dp),
                        interceptInput = intercept.value) {
                        Box(Modifier.fillMaxWidth().height(56.dp))
                        Box(Modifier.size(48.dp).clickable { actionClicks++ })
                    }
                }
            }
        }
        try {
            scene.render().close()
            scene.tap(140f, 80f) // Actual foreground gap; no click-through.
            assertEquals(0, backgroundClicks)
            scene.tap(20f, 80f)
            assertEquals(1, actionClicks)
            scene.tap(220f, 80f) // Beside the pinned region.
            scene.tap(140f, 125f) // Below it but inside the painted fade.
            assertEquals(2, backgroundClicks)
            intercept.value = false
            Snapshot.sendApplyNotifications()
            scene.render(16_000_000).close()
            scene.tap(140f, 80f)
            assertEquals(3, backgroundClicks)
        } finally { scene.close() }
    }

    @Test
    fun circularFabKeepsItsTouchTargetAndRippleInsidePaintedCircle() {
        for (profile in CeilingsFabProfile.entries) {
            var clicks = 0
            val enabled = mutableStateOf(true)
            val size = profile.drawSize.value
            val scene = ImageComposeScene(100, 100) {
                CeilingsTheme(false) {
                    Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.TopStart) {
                        CeilingsFab(onClick = { clicks++ }, actionLabel = "Test action",
                            profile = profile, enabled = enabled.value, containerColor = Color.White) {}
                    }
                }
            }
            try {
                val corner = ((profile.drawSize - profile.circleSize).value / 2 + 1).toInt()
                val restImage = scene.render()
                val restBitmap = Bitmap.makeFromImage(restImage)
                val outsideCircleColor = try { restBitmap.getColor(corner, corner) }
                    finally { restBitmap.close(); restImage.close() }
                scene.tap(size / 2, size / 2)
                assertEquals(1, clicks)
                scene.tap(1f, 1f)
                assertEquals(1, clicks, "Shadow margin/circle corner is not a button")
                scene.sendPointerEvent(PointerEventType.Press, Offset(size / 2, size / 2), type = PointerType.Touch)
                scene.render(50_000_000).close()
                val image = scene.render(150_000_000)
                try {
                    val bitmap = Bitmap.makeFromImage(image)
                    try {
                        // Inside the circle's bounding square but outside its
                        // circular outline: the shadow pixel must not acquire ripple.
                        assertEquals(outsideCircleColor, bitmap.getColor(corner, corner))
                    } finally { bitmap.close() }
                } finally { image.close() }
                scene.sendPointerEvent(PointerEventType.Release, Offset(size / 2, size / 2), type = PointerType.Touch)
                enabled.value = false
                Snapshot.sendApplyNotifications()
                scene.render(200_000_000).close()
                val before = clicks
                scene.tap(size / 2, size / 2)
                assertEquals(before, clicks)
            } finally { scene.close() }
        }
    }

    @Test
    fun gradientsRenderBothDirectionsWithoutOpaqueStripsInEitherTheme() {
        for (dark in listOf(false, true)) for (edge in CeilingsEdge.entries) {
            val scene = ImageComposeScene(20, 100) {
                CeilingsTheme(dark) {
                    // Red makes accidental opaque backgrounds observable.
                    Box(Modifier.fillMaxSize().background(Color.Red)) {
                        CeilingsEdgeGradient(edge, Modifier.fillMaxSize())
                    }
                }
            }
            try {
                val image = scene.render()
                try {
                    val bitmap = Bitmap.makeFromImage(image)
                    try {
                        val clearY = if (edge == CeilingsEdge.TOP) 99 else 0
                        val shadeY = if (edge == CeilingsEdge.TOP) 0 else 99
                        val clear = bitmap.getColor(10, clearY)
                        val shade = bitmap.getColor(10, shadeY)
                        assertTrue(org.jetbrains.skia.Color.getR(clear) > 250)
                        assertTrue(org.jetbrains.skia.Color.getG(clear) < 3)
                        // 50% blend keeps a substantial red contribution in both themes.
                        assertTrue(org.jetbrains.skia.Color.getR(shade) > 120)
                        assertTrue(org.jetbrains.skia.Color.getG(shade) < 150)
                    } finally { bitmap.close() }
                } finally { image.close() }
            } finally { scene.close() }
        }
    }

    private fun ImageComposeScene.tap(x: Float, y: Float) {
        sendPointerEvent(PointerEventType.Press, Offset(x, y), type = PointerType.Touch)
        sendPointerEvent(PointerEventType.Release, Offset(x, y), type = PointerType.Touch)
    }
}
