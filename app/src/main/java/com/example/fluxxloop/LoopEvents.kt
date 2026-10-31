package com.example.fluxxloop

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import java.util.LinkedList

private const val SEC = 1_000_000_000L

enum class EventType {
    WallpaperLaunch, Tap, ScreenOff, ScreenOn, UserPresent, BatteryLow, BatteryOkay, Fling,
    PowerConnected, PowerDisconnected, FpaPeek, FpaError, XperiaNotification, NormalNotification
}

class EventEnv(val renderer: LoopsRenderer, val context: Context)

private data class Pt(val x: Float, val y: Float)

/** Gemeinsamer Ereignis-Vorfahre (Original: a/c). */
abstract class LoopEvent(val type: EventType, val name: String) {
    var endNs = 0L           // e
    var fireNs = 0L          // f
    val createdNs = System.nanoTime() // d
    var fired = false        // g

    /** Wartezeit in Frames bis zum nächsten Render (0 = jeden Frame, Long.MAX_VALUE = fertig). */
    open fun delayFrames(): Long {
        val now = System.nanoTime()
        val span = fireNs - createdNs
        if (now > createdNs + (span * 0.6)) return 1L
        return if (now.toDouble() > (span * 0.1) + createdNs) 0L else 1L
    }

    open fun fire(env: EventEnv) { fired = true }
    open fun cancel() {}
    open fun priorityOverride(): Int = 0
}

private fun orientedPoint(portrait: Pt, land1: Pt, land3: Pt): Pt =
    when (Orientation.value) { 1 -> land1; 3 -> land3; else -> portrait }

// ------------------------------------------------------------------------------------ Ereignisse

class WallpaperLaunchEvent : LoopEvent(EventType.WallpaperLaunch, "WallpaperLaunch") {
    init { endNs = System.nanoTime() + SEC }
    override fun delayFrames() = 0L
    override fun fire(env: EventEnv) {
        super.fire(env)
        endNs = System.nanoTime() + SEC
        fireNs = endNs
        env.renderer.homeGradient(SEC)
        for (i in 0 until 5) env.renderer.addCircle(0f, 0f)
    }
}

class TapEvent(private val x: Float, y: Float) : LoopEvent(EventType.Tap, "Tap") {
    private val y = -y
    init { endNs = System.nanoTime() + SEC }
    override fun fire(env: EventEnv) {
        super.fire(env)
        endNs = System.nanoTime() + SEC
        fireNs = endNs
        env.renderer.addCircle(x, y)
    }
}

/** Bildschirm aus. Original: a/n (Normalfall) und a/o (Laden oder Akku < 16 %). */
class ScreenOffEvent(private val pane: Float, private val low: Boolean) : LoopEvent(EventType.ScreenOff, "ScreenOff") {
    init { endNs = Long.MAX_VALUE }
    override fun delayFrames() = if (System.nanoTime() < fireNs) 0L else Long.MAX_VALUE
    override fun fire(env: EventEnv) {
        super.fire(env)
        fireNs = System.nanoTime() + 100_000_000L
        val p = if (low) {
            when (Orientation.value) {
                1 -> Pt(2.8f + pane, 0f)
                3 -> Pt(-2.8f - pane, 0f)
                else -> Pt(0f + 0.8f * pane, -2.8f)
            }
        } else {
            when (Orientation.value) {
                1 -> Pt(-0.5f + pane, 1.5f)
                3 -> Pt(-1.5f - pane, 0.3f)
                else -> Pt(2.0f + 0.8f * pane, 0.3f)
            }
        }
        env.renderer.move(p.x, p.y, 0f, 0f, 0f, 100_000_000L, 0)
        env.renderer.lockGradient(100_000_000L)
    }
}

/** Bildschirm an, Sperre aktiv. Original: a/q (Akku niedrig), a/r (Laden), a/s (normal). */
class ScreenOnEvent(private val level: Float, private val pane: Float, private val charging: Boolean) :
    LoopEvent(EventType.ScreenOn, "ScreenOn") {
    private val low = level < 0.16f
    init { endNs = Long.MAX_VALUE }
    override fun priorityOverride(): Int = if (charging || low) 3 else 2

    override fun delayFrames(): Long = when {
        charging -> 0L
        System.nanoTime() < fireNs -> super.delayFrames()
        else -> Long.MAX_VALUE
    }

    override fun fire(env: EventEnv) {
        super.fire(env)
        val r = env.renderer
        when {
            charging -> {
                fireNs = System.nanoTime() + 2 * SEC
                r.recolor(level)
                r.levelGradient(2 * SEC, level)
                r.moveFrom(pane + 0f, -2.8f, pane + 0f, -2.8f, 2.4f, 2.6f, 0.1f, 2 * SEC, 2)
            }
            low -> {
                fireNs = System.nanoTime() + 300_000_000L
                r.recolor(level)
                r.levelGradient(300_000_000L, level)
                r.moveFrom(pane + 0f, -2.8f, pane + 0f, -2.8f, 2.0f, 2.1f, 0.9f, 300_000_000L, 3)
            }
            else -> {
                fireNs = System.nanoTime() + SEC
                r.recolor(level)
                r.moveFrom(pane * 0.8f + 2.0f, 0.3f, pane * 0.8f + 1.5f, 0.3f, 1.2f, 1.3f, 0.1f, SEC, 0)
                r.lockGradient(SEC)
            }
        }
    }
}

/** Entsperrt. Original: a/w (Laden), a/v (Akku niedrig), a/x (normal). */
class UserPresentEvent(private val level: Float, private val pane: Float, private val charging: Boolean) :
    LoopEvent(EventType.UserPresent, "UserPresent") {
    private val low = level < 0.16f

    init {
        endNs = System.nanoTime() + when {
            charging -> 3 * SEC
            low -> SEC
            else -> 2 * SEC
        }
    }
    override fun priorityOverride(): Int = if (charging || low) 3 else 1
    override fun delayFrames(): Long = when {
        charging -> 0L
        low -> super.delayFrames()
        else -> if (System.nanoTime().toDouble() > ((fireNs - createdNs) * 0.6) + createdNs) 1L else 0L
    }

    override fun fire(env: EventEnv) {
        super.fire(env)
        val r = env.renderer
        when {
            charging -> {
                endNs = System.nanoTime() + 3 * SEC
                fireNs = endNs
                val p = when (Orientation.value) {
                    1 -> Pt(2.8f + pane, 0f)
                    3 -> Pt(-2.8f - pane, 0f)
                    else -> Pt(0f + pane, -2.8f)
                }
                r.recolor(level)
                r.levelGradient(3 * SEC, level)
                r.move(p.x, p.y, 2.4f, 2.6f, 0.1f, 3 * SEC, 2)
            }
            low -> {
                endNs = System.nanoTime() + SEC
                fireNs = endNs
                val p = when (Orientation.value) {
                    1 -> Pt(2.8f + pane, 0f)
                    3 -> Pt(-2.8f - pane, 0f)
                    else -> Pt(0f + pane, -2.8f)
                }
                r.recolor(level)
                r.levelGradient(SEC, level)
                r.move(p.x, p.y, 2.0f, 2.1f, 0.9f, SEC, 3)
            }
            else -> {
                endNs = System.nanoTime() + 2 * SEC
                fireNs = endNs
                val p = when (Orientation.value) {
                    1 -> Pt(-0.5f + pane, 1.5f)
                    3 -> Pt(-1.5f - pane, 0.3f)
                    else -> Pt(1.5f + 0.8f * pane, 0.3f)
                }
                r.recolor(level)
                r.levelGradient(2 * SEC, level)
                r.move(p.x, p.y, 0.54f, 1.5f, 1.8f, 0.1f, 2 * SEC, 0)
            }
        }
    }
}

class BatteryLowEvent : LoopEvent(EventType.BatteryLow, "BatteryLow") {
    init { endNs = Long.MAX_VALUE }
    override fun delayFrames() = if (System.nanoTime() < fireNs) super.delayFrames() else Long.MAX_VALUE
    override fun fire(env: EventEnv) {
        super.fire(env)
        fireNs = System.nanoTime() + SEC
        val p = orientedPoint(Pt(0f, -2.8f), Pt(2.8f, 0f), Pt(-2.8f, 0f))
        val level = Battery.level(env.context)
        env.renderer.recolor(level)
        env.renderer.move(p.x, p.y, 2.0f, 2.1f, 0.9f, SEC, 3)
        env.renderer.levelGradient(SEC, level)
    }
}

class BatteryOkayEvent : LoopEvent(EventType.BatteryOkay, "BatteryOkay") {
    init { endNs = System.nanoTime() + SEC }
    override fun fire(env: EventEnv) {
        super.fire(env)
        endNs = System.nanoTime() + SEC
        fireNs = endNs
        val p = orientedPoint(Pt(0f, -1.8f), Pt(1.8f, 0f), Pt(-1.8f, 0f))
        val level = Battery.level(env.context)
        env.renderer.recolor(level)
        env.renderer.move(p.x, p.y, 2.0f, 2.1f, 0.6f, SEC, 3)
        env.renderer.levelGradient(SEC, level)
    }
}

class FlingEvent(private val dx: Float, private val dy: Float) : LoopEvent(EventType.Fling, "Fling") {
    init { endNs = System.nanoTime() + SEC }
    override fun fire(env: EventEnv) {
        super.fire(env)
        endNs = System.nanoTime() + SEC
        fireNs = endNs
        env.renderer.offsetCircles(dx, dy)
    }
}

/** Ladegerät angeschlossen (Original: a/j). Wiederholt sich periodisch, solange es lädt. */
class PowerConnectedEvent(private val manager: EventManager?) : LoopEvent(EventType.PowerConnected, "PowerConnected") {
    private val handler = Handler(Looper.getMainLooper())
    private var repeatAllowed = false
    private val repeat = Runnable {
        repeatAllowed = true
        if (manager != null) {
            fired = false
            manager.start()
        }
    }

    init { endNs = Long.MAX_VALUE }

    override fun delayFrames(): Long = if (!fired || System.nanoTime() < fireNs) 0L else 180L

    override fun fire(env: EventEnv) {
        super.fire(env)
        fireNs = System.nanoTime() + 3 * SEC
        val delayMs: Long
        if (Battery.isCharging(env.context) || !repeatAllowed) {
            val level = Battery.level(env.context)
            env.renderer.recolor(level)
            val p = orientedPoint(Pt(0f, -2.8f), Pt(2.8f, 0f), Pt(-2.8f, 0f))
            env.renderer.move(p.x, p.y, 2.4f, 2.6f, 0.1f, 3 * SEC, 2)
            env.renderer.levelGradient(3 * SEC, level)
            delayMs = 6000
        } else {
            delayMs = 30000
        }
        handler.postDelayed(repeat, delayMs)
    }

    override fun cancel() {
        handler.removeCallbacks(repeat)
        super.cancel()
    }
}

class PowerDisconnectedEvent : LoopEvent(EventType.PowerDisconnected, "PowerDisconnected") {
    init { endNs = System.nanoTime() + 2 * SEC }
    override fun fire(env: EventEnv) {
        super.fire(env)
        endNs = System.nanoTime() + 2 * SEC
        fireNs = endNs
        val p = orientedPoint(Pt(0f, -0.5f), Pt(0.5f, 0f), Pt(-0.5f, 0f))
        val level = Battery.level(env.context)
        if (level >= 0.16f) {
            env.renderer.recolor()
            env.renderer.homeGradient(2 * SEC)
        } else {
            env.renderer.recolor(level)
            env.renderer.levelGradient(2 * SEC, level)
        }
        env.renderer.move(p.x, p.y, 1.5f, 1.6f, 0.1f, 2 * SEC, 3)
    }
}

/** Fingerabdrucksensor berührt (Original: a/g). */
class FpaPeekEvent(private val pane: Float) : LoopEvent(EventType.FpaPeek, "FPAPeek") {
    init { endNs = System.nanoTime() + SEC }
    override fun fire(env: EventEnv) {
        super.fire(env)
        endNs = System.nanoTime() + SEC
        fireNs = endNs
        env.renderer.recolor()
        env.renderer.move(0.8f * pane + 1.5f, 0.3f, 1.2f, 1.3f, 0.2f, SEC, 0)
    }
}

/** Fingerabdruck nicht erkannt (Original: a/f). */
class FpaErrorEvent(private val pane: Float) : LoopEvent(EventType.FpaError, "FPAError") {
    init { endNs = System.nanoTime() + 500_000_000L }
    override fun fire(env: EventEnv) {
        super.fire(env)
        endNs = System.nanoTime() + 500_000_000L
        fireNs = endNs
        env.renderer.recolor()
        env.renderer.move(0.8f * pane + 1.5f, 0.3f, 1.2f, 1.3f, 0.9f, 500_000_000L, 0)
    }
}

class NotificationEvent(x: Float, y: Float, type: EventType = EventType.XperiaNotification) :
    LoopEvent(type, "Notification") {
    private val pt = Pt(x, 6f * y - 3f)
    init { endNs = System.nanoTime() + SEC }
    override fun fire(env: EventEnv) {
        super.fire(env)
        endNs = System.nanoTime() + SEC
        fireNs = endNs
        env.renderer.recolor()
        env.renderer.move(pt.x, pt.y, 2.0f, 2.1f, 0.1f, SEC, 2)
    }
}

// ------------------------------------------------------------------------------------ Verwaltung

interface EventHost {
    val wallpaperVisible: Boolean
    var touchEnabled: Boolean
    fun requestRender()
    fun onEventEnded(type: EventType)
}

/** Ereignis-Warteschlange (Original: Klasse d, Choreographer-getrieben). */
class EventManager(private val host: EventHost, private val env: EventEnv) : Choreographer.FrameCallback {
    private val choreographer = Choreographer.getInstance()
    private val list = LinkedList<LoopEvent>()
    private var maxEndNs = 0L // f

    private fun priority(e: LoopEvent): Int = when (e.type) {
        EventType.Tap, EventType.Fling, EventType.XperiaNotification, EventType.NormalNotification -> 1
        EventType.FpaPeek, EventType.FpaError, EventType.PowerDisconnected -> 2
        EventType.WallpaperLaunch, EventType.BatteryLow, EventType.BatteryOkay, EventType.PowerConnected -> 3
        EventType.ScreenOff -> 4
        EventType.ScreenOn, EventType.UserPresent -> e.priorityOverride()
    }
    private fun screenOffIdle(): Boolean {
        for (e in list) if (e.type == EventType.ScreenOff && e.delayFrames() == Long.MAX_VALUE) return false
        return true
    }

    private fun evaluate(): Long {
        var top = 0
        var result = Long.MAX_VALUE
        synchronized(list) {
            for (e in list) top = maxOf(top, priority(e))
            for (e in list) {
                val p = priority(e)
                if (e.fired) {
                    val d = e.delayFrames()
                    if (top == p && host.wallpaperVisible) result = minOf(result, d)
                } else {
                    if (top <= p && (host.wallpaperVisible || p == 3)) {
                        e.fire(env)
                        if (e.type == EventType.BatteryLow) {
                            host.touchEnabled = false
                            env.renderer.setTouch(-100f, -100f, 1f)
                        }
                        result = minOf(result, e.delayFrames())
                    }
                    if (maxEndNs < e.endNs) maxEndNs = e.endNs
                }
            }
        }
        return result
    }

    fun start() {
        choreographer.removeFrameCallback(this)
        choreographer.postFrameCallback(this)
    }

    fun post(e: LoopEvent) {
        synchronized(list) { list.add(e) }
    }

    fun has(type: EventType): Boolean = synchronized(list) { list.any { it.type == type } }

    fun remove(type: EventType) {
        synchronized(list) {
            val it = list.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.type == type) {
                    e.cancel()
                    host.onEventEnded(e.type)
                    it.remove()
                }
            }
        }
    }

    fun clear() {
        synchronized(list) {
            val it = list.iterator()
            while (it.hasNext()) { it.next().cancel(); it.remove() }
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!env.renderer.ready) {
            choreographer.postFrameCallback(this)
            return
        }
        synchronized(list) {
            val it = list.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (frameTimeNanos > e.endNs) {
                    host.onEventEnded(e.type)
                    it.remove()
                }
            }
        }
        val delay = evaluate()
        if (frameTimeNanos < maxEndNs) {
            if (screenOffIdle()) host.requestRender()
            if (delay == 0L) {
                choreographer.postFrameCallback(this)
            } else if (delay != Long.MAX_VALUE) {
                choreographer.postFrameCallbackDelayed(this, (delay * 16.666 + 1.0).toLong())
            }
        }
    }
}
