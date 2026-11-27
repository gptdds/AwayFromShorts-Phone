package com.afs.style.api

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.Offset

class UniverseGesture {
    data class Sample(val position: Offset, val time: Long)
    val trail = mutableStateListOf<Sample>()
    var releaseVelocity by mutableStateOf(Offset.Zero); private set
    private var sampleTime = 0L
    var position by mutableStateOf(Offset(.5f, .5f)); private set
    var previous by mutableStateOf(position); private set
    var held by mutableStateOf(false); private set
    var started by mutableStateOf(0L); private set
    var released by mutableStateOf(0L); private set
    fun accept(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        previous = position
        position = Offset(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        if (action == MotionEvent.ACTION_MOVE && held) {
            val elapsed = (now - sampleTime).coerceAtLeast(1L) / 1000f
            releaseVelocity = (position - previous) / elapsed
            if (trail.size >= 24) trail.removeAt(0)
            trail.add(Sample(position, now))
        }
        sampleTime = now
        when (action) {
            MotionEvent.ACTION_DOWN -> { held = true; started = now; releaseVelocity = Offset.Zero; trail.clear() }
            MotionEvent.ACTION_UP -> { held = false; released = SystemClock.uptimeMillis() }
            MotionEvent.ACTION_CANCEL -> clear()
        }
    }
    fun clear() { held = false; released = 0L; releaseVelocity = Offset.Zero; trail.clear() }
}
