package ch.schreibwerkstatt.mobile.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign

/**
 * Horizontale „Blätter"-Geste über beliebigem Inhalt – auch über einer WebView,
 * weil im [PointerEventPass.Initial] entschieden und konsumiert wird (so kommt
 * eine eindeutig horizontale Wischbewegung dem Kind zuvor, während vertikales
 * Scrollen und Taps unangetastet durchgereicht werden).
 *
 * Wisch nach links → [onNext], nach rechts → [onPrev]. Ist ein Callback `null`,
 * gibt es in diese Richtung keine Nachbarseite und die Geste bleibt inaktiv.
 *
 * Geblättert wird nur bei einem echten Fling: Strecke über der Schwelle UND
 * horizontale Endgeschwindigkeit über [MIN_FLING_VELOCITY_DP_S]. Ein langsames
 * Ziehen (z.B. beim Markieren im Text) wechselt den Abschnitt nicht. Bewegt sich
 * der Finger erst nach dem Long-Press-Timeout, ist das eine Textauswahl der
 * WebView — dann bleibt die Geste unberührt und nichts wird konsumiert.
 */
fun Modifier.pageFlipGestures(
    onPrev: (() -> Unit)?,
    onNext: (() -> Unit)?,
): Modifier = composed {
    val vc = LocalViewConfiguration.current
    val touchSlop = vc.touchSlop
    val longPressMs = vc.longPressTimeoutMillis
    pointerInput(onPrev, onNext) {
        // Mindeststrecke, ab der ein horizontaler Wisch als Blättern gilt.
        val distanceThreshold = 96.dp.toPx()
        // Mindestgeschwindigkeit (px/s) der Wischbewegung beim Loslassen.
        val velocityThreshold = MIN_FLING_VELOCITY_DP_S.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val tracker = VelocityTracker()
            tracker.addPointerInputChange(down)
            var horizontal = false
            var bailed = false
            var totalX = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPointerInputChange(change)
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                totalX = dx
                if (!horizontal && !bailed) {
                    val moved = abs(dx) > touchSlop || abs(dy) > touchSlop
                    when {
                        // Erste Bewegung erst nach dem Long-Press: Textauswahl der
                        // WebView (Markierungsgriffe) — nicht anfassen.
                        moved && change.uptimeMillis - down.uptimeMillis >= longPressMs -> bailed = true
                        abs(dy) > touchSlop && abs(dy) >= abs(dx) -> bailed = true // vertikal → WebView scrollen lassen
                        abs(dx) > touchSlop && abs(dx) > abs(dy) -> horizontal = true
                    }
                }
                if (horizontal) change.consume()
                if (!change.pressed) break
            }
            if (horizontal && abs(totalX) > distanceThreshold) {
                val vx = tracker.calculateVelocity().x
                // Fling in Wischrichtung: wer zurückzieht, blättert nicht.
                if (abs(vx) > velocityThreshold && sign(vx) == sign(totalX)) {
                    if (totalX < 0) onNext?.invoke() else onPrev?.invoke()
                }
            }
        }
    }
}

/** Mindestgeschwindigkeit eines Blätter-Flings in dp/s. */
private const val MIN_FLING_VELOCITY_DP_S = 1000
