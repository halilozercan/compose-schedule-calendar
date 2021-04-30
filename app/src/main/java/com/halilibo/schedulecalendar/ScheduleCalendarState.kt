package com.halilibo.schedulecalendar

import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.threeten.bp.LocalDateTime
import org.threeten.bp.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToLong

@Composable
fun rememberScheduleCalendarState(
    referenceDateTime: LocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.DAYS)
): ScheduleCalendarState {
    val coroutineScope = rememberCoroutineScope()
    return remember(coroutineScope) {
        ScheduleCalendarState(
            referenceDateTime = referenceDateTime,
            coroutineScope = coroutineScope,
        )
    }
}

class ScheduleCalendarState(
    referenceDateTime: LocalDateTime,
    private val coroutineScope: CoroutineScope
) {
    val startDateTime: LocalDateTime by derivedStateOf {
        referenceDateTime.plusSeconds(secondsOffset.value)
    }

    val endDateTime: LocalDateTime by derivedStateOf {
        startDateTime.plusSeconds(this.viewSpanSeconds.value)
    }

    private val viewSpanSeconds = Animatable(ChronoUnit.DAYS.duration.seconds, LongToVector)
    private val secondsOffset = Animatable(0L, LongToVector)
    private val width = mutableStateOf(1)

    internal fun updateView(newViewSpanSeconds: Int, newWidth: Int) {
        this.width.value = newWidth
        coroutineScope.launch {
            viewSpanSeconds.animateTo(newViewSpanSeconds.toLong())
        }
        coroutineScope.launch {
            updateAnchors(newViewSpanSeconds)
        }
    }

    internal val scrollableState = ScrollableState {
        coroutineScope.launch {
            secondsOffset.snapTo(secondsOffset.value - it.toSeconds())
        }
        it
    }

    internal val secondsInPx by derivedStateOf {
        this.viewSpanSeconds.value.toFloat() / width.value.toFloat()
    }

    private fun Float.toSeconds(): Long {
        return (this * secondsInPx).roundToLong()
    }

    private fun Long.toPx(): Float {
        return this / secondsInPx
    }

    internal val scrollFlingBehavior = object : FlingBehavior {
        val decay = exponentialDecay<Float>(frictionMultiplier = 2f)
        override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
            val endPosition = decay.calculateTargetValue(0f, -initialVelocity)
            val flungPosition = findSettlePosition(secondsOffset.value.toPx() + endPosition)
            secondsOffset.animateTo(flungPosition.toSeconds())
            return 0f
        }
    }

    internal val visibleHours by derivedStateOf {
        val startHour = startDateTime.truncatedTo(ChronoUnit.HOURS)
        val endHour = endDateTime.truncatedTo(ChronoUnit.HOURS).plusHours(1)

        if (anchorRangeSeconds == 24 * 3600L) {
            emptyList()
        } else {
            startHour.between(endHour) { plusHours(1L) }.filter {
                it.hour % (anchorRangeSeconds / 3600) == 0L && it.hour != 0
            }.toList()
        }
    }

    private var anchorRangeSeconds by mutableStateOf(Long.MAX_VALUE)
    private var anchorRangePx by mutableStateOf(Float.MAX_VALUE)
    private suspend fun updateAnchors(viewSpanInSeconds: Int) {
        anchorRangeSeconds = if (viewSpanInSeconds > 24 * 3600) {
            24 * 3600
        } else if (viewSpanInSeconds <= 24 * 3600 && viewSpanInSeconds > 12 * 3600) {
            6 * 3600
        } else if (viewSpanInSeconds <= 12 * 3600 && viewSpanInSeconds > 6 * 3600) {
            3 * 3600
        } else {
            3600
        }
        anchorRangePx = anchorRangeSeconds.toPx()
        val newAnchoredPosition = findSettlePosition(secondsOffset.value.toPx())
        secondsOffset.animateTo(newAnchoredPosition.toSeconds())
    }

    private fun findSettlePosition(target: Float): Float {
        if (target == 0f) return 0f

        val nearestAnchor = target - (target % anchorRangePx)
        val nearestAnchor2 = nearestAnchor + anchorRangePx
        return (abs(target - nearestAnchor) to abs(target - nearestAnchor2)).let {
            if (it.first > it.second) nearestAnchor2 else nearestAnchor
        }
    }

    internal fun offsetFraction(localDateTime: LocalDateTime): Float {
        return ChronoUnit.SECONDS.between(startDateTime, localDateTime).toFloat() / (viewSpanSeconds.value).toFloat()
    }

    // width to offsetX
    internal fun widthAndOffsetForEvent(
        start: LocalDateTime, end: LocalDateTime, totalWidth: Int
    ): Pair<Int, Int> {
        val startOffsetPercent = offsetFraction(start).coerceIn(0f, 1f)
        val endOffsetPercent = offsetFraction(end).coerceIn(0f, 1f)

        val width = ((endOffsetPercent - startOffsetPercent) * totalWidth).toInt() + 1
        val offsetX = (startOffsetPercent * totalWidth).toInt()
        return width to offsetX
    }
}

private val LongToVector: TwoWayConverter<Long, AnimationVector1D> =
    TwoWayConverter({ AnimationVector1D(it.toFloat()) }, { it.value.toLong() })