package com.example.fluxxloop

import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import kotlin.math.max
import kotlin.math.min

/** Zeitgeber (Original: Klasse c/j). Zeiten in Nanosekunden. */
class Anim {
    var active = false
    var interpolator: Interpolator? = null
    private var startNs = 0L
    private var endNs = 0L
    private var e = 0f

    fun update() {
        val now = System.nanoTime()
        e = if (startNs < now) (now - startNs).toFloat() / (endNs - startNs).toFloat() else 0f
        if (e > 1f) active = false
    }

    fun start(durationNs: Long) {
        e = 0f
        startNs = System.nanoTime()
        endNs = startNs + durationNs
        active = true
    }

    fun start(delayNs: Long, durationNs: Long) {
        e = 0f
        startNs = System.nanoTime() + delayNs
        endNs = startNs + durationNs
        active = true
    }

    fun value(): Float {
        val i = interpolator
        return if (i != null) min(1f, i.getInterpolation(e)) else min(1f, e)
    }
}

/** Zustand einer Schleife (Original: Klasse c/e). */
class LoopState {
    val points = ArrayList<Float>()                 // 12 Bezier-Punkte (x,y)
    var rot = floatArrayOf(0f, 0f, 0f)
    var scale = 0f
    var pos = floatArrayOf(0f, 0f, 0f, 0f)
    val color = Array(2) { FloatArray(3) }
    var alpha = 0f
}

/** Gemeinsame Vertex-/Indexdaten aller 6 Schleifen (Original: c/f). */
class LoopShared {
    val vertices = FloatArray(288)
    val indices = ByteArray(192)
}

/** Eine Schleife (Original: Klasse c/d). */
class Loop(
    private val shared: LoopShared,
    x: Float,
    y: Float,
    /** Zielgröße (Original: j) */
    val sizeLimit: Float,
    /** Linienbreite (Original: i) */
    val width: Float,
    val slot: Int,
) {
    val target = LoopState()    // a
    val current = LoopState()   // b
    val start = LoopState()     // c
    val anim = Anim()           // d
    val matrix = FloatArray(16) // e
    val lock = Any()
    var depth = 0f              // h
    var fading = false          // k
    var lastPhase = 0f

    init {
        synchronized(lock) {
            buildShape(0f)
            start.pos = floatArrayOf(x, y, 0f, 0f)
            target.pos = floatArrayOf(x, y, 0f, 0f)
            current.pos = floatArrayOf(x, y, 0f, 0f)
            current.scale = 0.4f * sizeLimit
            current.points.clear()
            current.points.addAll(target.points)
        }
    }

    private fun noise(f: Float): Float {
        val r = (Math.random().toFloat() * f) - (f / 2f)
        return if (r >= f / 6f || r <= f / -6f) r else 0f
    }

    private fun uploadGeometry() {
        synchronized(lock) {
            val idx = ByteArray(32)
            for (i in 0 until 4) {
                for (i2 in 0 until 4) {
                    idx[(i * 4 * 2) + i2] = ((slot * 24) + (((i * 2) * 3 + (i2 * 2)) % 24)).toByte()
                }
                for (i3 in 4 until 8) {
                    idx[(i * 4 * 2) + i3] = ((slot * 24) + ((((i * 2) * 3) + ((i3 - 4) * 2) + 1) % 24)).toByte()
                }
            }
            val from = start.points
            val to = target.points
            var i5 = 0
            while (i5 < 48) {
                shared.vertices[(slot * 48) + i5] = from[i5 / 2]
                shared.vertices[(slot * 48) + i5 + 1] = from[(i5 / 2) + 1]
                shared.vertices[(slot * 48) + i5 + 2] = to[i5 / 2]
                shared.vertices[(slot * 48) + i5 + 3] = to[(i5 / 2) + 1]
                i5 += 4
            }
            System.arraycopy(idx, 0, shared.indices, slot * 32, 32)
        }
    }

    /** Neue zufällige Zielform erzeugen (Original: d.b(float)). */
    private fun buildShape(f: Float) {
        for (i in 0 until 2) {
            current.rot[i] = (current.rot[i].toDouble() % Math.PI).toFloat()
            target.rot[i] = (0.39269908169872414 * ((Math.random() * 2.0) - 1.0)).toFloat()
        }
        val a = target.points
        a.clear()
        a.add(1f); a.add(0f)
        a.add(0f); a.add(0f)
        a.add(0.55228f + noise(f)); a.add(noise(f) + 1f)
        a.add(0f); a.add(1f)
        a.add(a[6] * 2f - a[4]); a.add(a[7] * 2f - a[5])
        a.add(noise(f) - 1f); a.add(0.55228f + noise(f))
        a.add(-1f); a.add(0f)
        a.add(a[12] * 2f - a[10]); a.add(a[13] * 2f - a[11])
        a.add(noise(f) - 0.55228f); a.add(noise(f) - 1f)
        a.add(0f); a.add(-1f)
        a.add(a[18] * 2f - a[16]); a.add(a[19] * 2f - a[17])
        a.add(noise(f) + 1f); a.add(noise(f) - 0.55228f)
        a[2] = a[0] * 2f - a[22]
        a[3] = a[1] * 2f - a[23]
        snapshotCurrent()
        uploadGeometry()
    }

    /** Aktuellen Zustand als Startzustand merken (Original: d.c()). */
    private fun snapshotCurrent() {
        start.points.clear()
        start.scale = current.scale
        start.alpha = current.alpha
        start.pos = floatArrayOf(current.pos[0], current.pos[1], current.pos[2], 0f)
        if (current.points.size > 0) start.points.addAll(current.points) else start.points.addAll(target.points)
        System.arraycopy(current.rot, 0, start.rot, 0, 3)
        for (i in 0 until 2) start.color[i] = current.color[i].copyOf(3)
    }

    /** Ausblenden einleiten (Original: d.a()). */
    fun fadeOut() {
        fading = true
        synchronized(lock) {
            target.pos[2] = depth + 0.3f
            target.alpha = 0f
            snapshotCurrent()
        }
        anim.interpolator = INTERPOLATORS[0]
        anim.start(1_000_000_000L)
    }

    /** Neue Position/Größe/Form (Original: d.a(f,f2,f3,f4,long,int)). */
    fun moveTo(x: Float, y: Float, newScale: Float, shape: Float, durationNs: Long, interp: Int) {
        if (fading) return
        synchronized(lock) {
            buildShape(shape)
            target.pos = floatArrayOf(x, y, depth, 0f)
            target.scale = newScale
        }
        anim.interpolator = INTERPOLATORS[interp]
        anim.start(0L, durationNs)
    }

    /** Verschiebung durch Wischen (Original: d.a(f,f2,f3,f4,long,long)). */
    fun offsetBy(dx: Float, dy: Float, minX: Float, maxX: Float, delayNs: Long, durationNs: Long) {
        if (fading) return
        synchronized(lock) {
            System.arraycopy(current.rot, 0, target.rot, 0, 3)
            target.points.clear()
            target.points.addAll(current.points)
            snapshotCurrent()
            uploadGeometry()
            target.pos[0] = max(minX, min(maxX, current.pos[0] + ((1.5f - depth) * dx)))
            target.pos[1] = max(-1.7f, min(1.7f, current.pos[1] - ((1.5f - depth) * dy)))
        }
        anim.interpolator = INTERPOLATORS[1]
        anim.start(delayNs, durationNs)
    }

    /** Farbe setzen (Original: d.a(int, b.c, boolean)). */
    fun setColor(index: Int, rgb: FloatArray, immediate: Boolean) {
        if (fading) return
        synchronized(lock) {
            target.color[index][0] = rgb[0]
            target.color[index][1] = rgb[1]
            target.color[index][2] = rgb[2]
            if (immediate) {
                current.color[index][0] = rgb[0]
                current.color[index][1] = rgb[1]
                current.color[index][2] = rgb[2]
            }
            target.alpha = 0.98f
        }
    }

    /** Aktuellen Animationsfortschritt auf [current] anwenden (Teil von l.a()). */
    fun applyAnimation(): Boolean {
        anim.update()
        if (!anim.active) return false
        val b = anim.value()
        synchronized(lock) {
            for (i in 0 until target.points.size) {
                current.points[i] = ((target.points[i] - start.points[i]) * b) + start.points[i]
            }
            for (i in 0 until 3) current.pos[i] = start.pos[i] + ((target.pos[i] - start.pos[i]) * b)
            current.scale = start.scale + ((target.scale - start.scale) * b)
            current.alpha = start.alpha + ((target.alpha - start.alpha) * b)
            for (i in 0 until 3) current.rot[i] = start.rot[i] + ((target.rot[i] - start.rot[i]) * b)
            for (i in 0 until 2) for (k in 0 until 3) {
                current.color[i][k] = start.color[i][k] + ((target.color[i][k] - start.color[i][k]) * b)
            }
        }
        lastPhase = anim.value()
        return true
    }

    companion object {
        val INTERPOLATORS: Array<Interpolator> = arrayOf(
            PathInterpolator(0.0f, 0.0f, 0.2f, 1.0f),
            PathInterpolator(0.075f, 0.0f, 0.2f, 1.0f),
            PathInterpolator(0.2f, 0.0f, 0.3f, 1.0f),
            PathInterpolator(0.7f, 0.0f, 0.3f, 1.0f),
        )
    }
}
