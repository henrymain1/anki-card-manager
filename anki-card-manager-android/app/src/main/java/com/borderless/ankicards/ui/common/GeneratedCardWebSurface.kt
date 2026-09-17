package com.borderless.ankicards.ui.common

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.borderless.ankicards.data.gemini.GeneratedMedia
import com.borderless.ankicards.domain.recipe.CardFace
import com.borderless.ankicards.domain.recipe.CardField
import com.borderless.ankicards.domain.recipe.NoteType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Full-fidelity card preview rendered via WebView so the preview matches
 * exactly what AnkiDroid will show post-save.
 *
 * Three notable design choices:
 *
 *  1. **Two WebViews always mounted.** Front and back HTML each get their
 *     own WebView, both loaded eagerly when the surface appears. A flip
 *     just changes opacity — no `loadDataWithBaseURL` during the flip,
 *     which was the cause of the visible mid-flip freeze.
 *
 *  2. **Height comes from JS, not native polling.** The WebView's
 *     `contentHeight` is unreliable when base64 images decode after page
 *     load. Instead each face's HTML is injected with a tiny script that
 *     uses ResizeObserver + image-load events to call back to native with
 *     the true scroll height as it changes. Measurements are always
 *     accurate, even with slow-decoding images.
 *
 *  3. **Box height = max(front, back).** Neither face is ever clipped,
 *     regardless of which is visible. The front view has empty space
 *     below shorter content, which beats the previous behavior of cutting
 *     off the audio button.
 *
 * Interaction:
 *   - Front/Back chips and Flip button → 3D rotation animation, swaps
 *     visibility at the 90° crossover.
 *   - Horizontal drag on the card → live rotation following the finger,
 *     snaps to nearest face on release.
 */
@Composable
fun GeneratedCardWebSurface(
    noteType: NoteType,
    textByFieldKey: Map<String, String>,
    mediaByFieldKey: Map<String, GeneratedMedia>,
    fieldErrors: Map<String, String>,
    regeneratingFieldKey: String?,
    onFieldClicked: (CardField) -> Unit,
    onFieldRegenerate: (CardField) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val rotation = remember { Animatable(0f) }
    val isFrontVisible by remember {
        derivedStateOf {
            val n = ((rotation.value % 360f) + 360f) % 360f
            n < 90f || n > 270f
        }
    }
    val visibleFace = if (isFrontVisible) CardFace.FRONT else CardFace.BACK

    // Build both faces' HTML eagerly. Memoized on inputs so re-renders are
    // cheap, and so the WebViews don't reload unless something they depend
    // on actually changed (per-field regeneration would change values and
    // therefore html; an irrelevant recomposition won't).
    val frontHtml = remember(noteType, textByFieldKey, mediaByFieldKey) {
        buildAnkiPreviewHtml(noteType, textByFieldKey, mediaByFieldKey, CardFace.FRONT)
    }
    val backHtml = remember(noteType, textByFieldKey, mediaByFieldKey) {
        buildAnkiPreviewHtml(noteType, textByFieldKey, mediaByFieldKey, CardFace.BACK)
    }

    // Each face's measured height is reset when its html changes. We use
    // the max so neither face is ever clipped — the user's report of "card
    // cut off at the audio button" was the back's measured height landing
    // smaller than its actual rendered content.
    var frontHeight by remember(frontHtml) { mutableStateOf<Dp?>(null) }
    var backHeight by remember(backHtml) { mutableStateOf<Dp?>(null) }

    fun flipBy180() {
        scope.launch {
            rotation.animateTo(
                targetValue = rotation.value + 180f,
                animationSpec = tween(durationMillis = 700)
            )
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        FaceSwitcher(
            visible = visibleFace,
            onSelect = { target -> if (target != visibleFace) flipBy180() }
        )
        Spacer(Modifier.size(8.dp))

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val cardWidth = maxWidth
            val fallbackHeight = cardWidth * 4 / 3
            // Max of measured heights so neither face clips. Fall back to a
            // 4:3 silhouette until at least one face has reported.
            val maxMeasured: Dp = maxOf(frontHeight ?: 0.dp, backHeight ?: 0.dp)
            val targetHeight = if (maxMeasured > 0.dp) maxMeasured else fallbackHeight
            val animatedHeight by animateDpAsState(
                targetValue = targetHeight,
                animationSpec = tween(durationMillis = 280),
                label = "card-height"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(animatedHeight)
                    .shadow(8.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { scope.launch { rotation.stop() } },
                            onDragEnd = { snapToNearestFace(scope, rotation) },
                            onDragCancel = { snapToNearestFace(scope, rotation) }
                        ) { change, dragAmount ->
                            // Sign convention: `+` here means a right-swipe
                            // (positive dragAmount) increases rotation. The
                            // first iteration used `-`, the user reported it
                            // felt backward, so it's `+` now. If a future
                            // user flips on this again, this is the only
                            // line to touch.
                            scope.launch {
                                rotation.snapTo(
                                    rotation.value + dragAmount * DragSensitivity
                                )
                            }
                            change.consume()
                        }
                    }
                    .graphicsLayer {
                        rotationY = rotation.value
                        cameraDistance = 14f * density.density
                    }
            ) {
                // ── Front face ──────────────────────────────────────────
                // Always mounted, alpha-gated. When the user flips, only
                // alpha changes — no reload, no measurement reset, no lag.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = if (isFrontVisible) 1f else 0f }
                ) {
                    CardWebView(
                        html = frontHtml,
                        onHeightMeasured = { frontHeight = it },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                // ── Back face ───────────────────────────────────────────
                // Same as front but pre-rotated 180° so it reads right-side
                // up (otherwise the outer rotation would leave it mirrored).
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = if (isFrontVisible) 0f else 1f
                            rotationY = 180f
                        }
                ) {
                    CardWebView(
                        html = backHtml,
                        onHeightMeasured = { backHeight = it },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // The Fields list that used to live here (one editable row per
        // field, with regenerate + tap-to-edit) was removed per user
        // request — the WebView preview is the only generator-screen
        // surface now. `onFieldClicked` / `onFieldRegenerate` parameters
        // are kept on this composable's signature so the editor sheet
        // pathway is still wired through the screen; nothing currently
        // triggers it. Re-adding manual editing means wiring per-field
        // tap targets back in (either inside the WebView via a JS bridge,
        // or via long-press / a button somewhere else).
    }
}

/**
 * Degrees rotated per pixel of horizontal drag. ~180° in ~400px of finger
 * travel — about one card width.
 */
private const val DragSensitivity = 0.45f

/** Animate to the nearest face boundary (multiple of 180°). */
private fun snapToNearestFace(
    scope: CoroutineScope,
    rotation: Animatable<Float, *>
) {
    scope.launch {
        val current = rotation.value
        val nearest = (current / 180f).roundToInt() * 180f
        rotation.animateTo(
            targetValue = nearest.toFloat(),
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        )
    }
}

/**
 * WebView wrapped for Compose, reporting its content height back to the
 * caller as soon as the page is laid out (and again every time the layout
 * changes — e.g. when a base64 image finishes decoding).
 *
 * Height reporting goes through a JavaScript bridge:
 *
 *  - JS is enabled (was disabled in v1, but ResizeObserver is the only
 *    reliable way to catch post-decode image height growth).
 *  - The HTML is rewritten on the way in to inject a small reporter
 *    script that calls back into [AnkiHeightBridge] with the scroll
 *    height on `load`, `resize`, image `load`, and any `ResizeObserver`
 *    fire.
 *  - The bridge marshals onto the main thread (the `@JavascriptInterface`
 *    callback comes in on the WebView's worker thread) and invokes
 *    [onHeightMeasured] with the converted Dp value.
 *
 * Security: we control the HTML entirely (no user-injected content); the
 * only exposed JS method is `onHeight(Int)` which does nothing but post
 * a height back. Safe.
 */
@Composable
private fun CardWebView(
    html: String,
    onHeightMeasured: (Dp) -> Unit,
    modifier: Modifier = Modifier
) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // Wrap the callback so the @JavascriptInterface (created once in the
    // factory block) always calls the LATEST callback even if the outer
    // composable recomposes with a different lambda identity.
    val callbackRef = remember { mutableStateOf(onHeightMeasured) }
    callbackRef.value = onHeightMeasured

    val bridge = remember {
        AnkiHeightBridge { pxHeight ->
            mainHandler.post {
                if (pxHeight > 0) {
                    // JS reports `document.documentElement.scrollHeight`,
                    // which is CSS pixels. On a WebView with
                    // `useWideViewPort = false`, the Android docs state the
                    // layout width is "device-independent (CSS) pixels" —
                    // i.e., 1 CSS px == 1 dp on this WebView. So we pass
                    // pxHeight straight through to .dp.
                    //
                    // The earlier version divided by `LocalDensity.density`
                    // on the assumption that pxHeight was physical pixels,
                    // which produced a value ~38% of true and clipped the
                    // box around the middle of the image.
                    android.util.Log.d(
                        "AnkiCardsPreview",
                        "JS reported height: $pxHeight CSS px → ${pxHeight}.dp"
                    )
                    callbackRef.value(pxHeight.dp)
                }
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = false
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                addJavascriptInterface(bridge, JsBridgeName)
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(
                null,
                injectHeightReporter(html),
                "text/html",
                "UTF-8",
                null
            )
        }
    )
}

private const val JsBridgeName = "AnkiHeight"

/**
 * Receives height reports from the injected script. Named class (not an
 * anonymous object) so the `@JavascriptInterface` annotation survives any
 * R8/ProGuard inspection — and so the constructor param + method name
 * don't collide. The JS calls `AnkiHeight.onHeight(<px>)`, which lands
 * here, which then invokes the stored [report] callback.
 *
 * Security: this bridge is registered on a WebView that only ever loads
 * static HTML we generate from the card type — no remote URLs, no user
 * input that reaches the page as code. The exposed method does nothing
 * but post an integer height back.
 */
private class AnkiHeightBridge(private val report: (Int) -> Unit) {
    @JavascriptInterface
    fun onHeight(pxHeight: Int) {
        report(pxHeight)
    }
}

/**
 * Splice a tiny height-reporting script in just before `</body>`. The
 * script keeps reporting as the body resizes — covers initial layout,
 * image decode, and any future dynamic content.
 */
private fun injectHeightReporter(html: String): String {
    val script = """
        <script>
        (function() {
            function reportHeight() {
                try {
                    var h = Math.max(
                        document.documentElement.scrollHeight,
                        document.body ? document.body.scrollHeight : 0,
                        document.documentElement.offsetHeight,
                        document.body ? document.body.offsetHeight : 0
                    );
                    if (window.$JsBridgeName && $JsBridgeName.onHeight) {
                        $JsBridgeName.onHeight(h);
                    }
                } catch (e) {}
            }
            // Audio playback handler for the .replay-button onclick on
            // audio fields. The renderer wires each button to call this
            // with a base64 data: URL. Re-using the same Audio instance
            // would risk crackle if the user mashes the button; cheap to
            // just create a new one each tap.
            window.__playAnkiPreviewAudio = function(dataUri) {
                try {
                    var a = new Audio(dataUri);
                    a.play();
                } catch (e) {}
            };
            // Fire on load, resize, and any subsequent layout change.
            window.addEventListener('load', reportHeight);
            window.addEventListener('resize', reportHeight);
            if (typeof ResizeObserver !== 'undefined') {
                try { new ResizeObserver(reportHeight).observe(document.body); } catch (e) {}
            }
            // Image decode happens after 'load' for base64 images, so also
            // hook every <img> tag's own load event.
            document.addEventListener('DOMContentLoaded', function() {
                var imgs = document.querySelectorAll('img');
                imgs.forEach(function(img) {
                    if (img.complete) {
                        reportHeight();
                    } else {
                        img.addEventListener('load', reportHeight);
                        img.addEventListener('error', reportHeight);
                    }
                });
                reportHeight();
            });
        })();
        </script>
    """.trimIndent()
    return if (html.contains("</body>")) {
        html.replace("</body>", "$script</body>")
    } else {
        html + script
    }
}

@Composable
private fun FaceSwitcher(visible: CardFace, onSelect: (CardFace) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FaceButton("Front", selected = visible == CardFace.FRONT) { onSelect(CardFace.FRONT) }
        FaceButton("Back", selected = visible == CardFace.BACK) { onSelect(CardFace.BACK) }
        Spacer(Modifier.weight(1f))
        FilledTonalButton(onClick = {
            onSelect(if (visible == CardFace.FRONT) CardFace.BACK else CardFace.FRONT)
        }) {
            Icon(Icons.Filled.Flip, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text("Flip")
        }
    }
}

@Composable
private fun FaceButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val container = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (selected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
    }
}

// FieldEditRow was removed alongside the Fields list. If manual editing
// gets a new home (e.g. tap inside the WebView, or a separate "Edit fields"
// screen), revive from git history rather than rewriting from scratch.
