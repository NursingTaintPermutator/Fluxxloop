package com.example.fluxxloop

import android.content.Context
import android.graphics.Color
import android.opengl.GLES32
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import android.view.animation.LinearInterpolator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

private const val TAG = "LoopsGL"

/** Drehung/Orientierung (Original: e/e). 0=Hochformat, 1/3=Querformat, 2=kopfüber. */
object Orientation {
    @Volatile
    var value = 0

    fun update(context: Context, portrait: Boolean): Int {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val rotation = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: 0
        value = when (rotation) {
            0, 1 -> if (!portrait) 1 else 0
            2, 3 -> if (!portrait) 3 else 2
            else -> 0
        }
        return value
    }
}

/** Akku-Hilfen (Original: e/a). */
object Battery {
    fun level(context: Context): Float {
        val i = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            ?: return 1f
        val level = i.getIntExtra("level", -1)
        val scale = i.getIntExtra("scale", -1)
        return if (level < 0 || scale <= 0) 1f else level.toFloat() / scale
    }

    fun isCharging(context: Context): Boolean {
        val i = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            ?: return false
        val s = i.getIntExtra("status", -1)
        return s == 2 || s == 5
    }
}

/** Farbwahl der Schleifen (Original: b/a). */
class LoopColors(val palette: LoopPalette) {
    private fun pickGroup(groups: List<IntArray>, i: Int): FloatArray {
        val g = groups[i % groups.size]
        val c = g[(Math.random() * g.size).toInt().coerceAtMost(g.size - 1)]
        return floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)
    }

    fun normal(i: Int): FloatArray = pickGroup(palette.normal!!, i)

    fun forLevel(i: Int, f: Float): FloatArray {
        if (Math.random() >= max(0.8f, 1.0f - f)) return normal(i)
        if (f < 0.1f && palette.empty != null) return pickGroup(palette.empty, i)
        if (f < 0.16f && palette.low != null) return pickGroup(palette.low, i)
        if (palette.chargingLoops != null) return pickGroup(palette.chargingLoops, i)
        return normal(i)
    }
}

/** Gradient-Zustand für den Hintergrund (Original: c/b). Punkte sind bereits invertiert (1 - p). */
class GradState(val colors: IntArray, val points: FloatArray) {
    companion object {
        fun from(g: Gradient) = GradState(g.colors, floatArrayOf(1f - g.points[0], 1f - g.points[1], 1f - g.points[2]))
        val EMPTY = GradState(IntArray(5), FloatArray(3))
    }
}

private class GlProgram(context: Context, vs: String, tcs: String?, tes: String?, gs: String?, fs: String) {
    val id: Int = GLES32.glCreateProgram()
    private val cache = HashMap<String, Int>()

    init {
        val shaders = ArrayList<Int>()
        shaders += attach(context, GLES32.GL_VERTEX_SHADER, vs)
        if (tcs != null) shaders += attach(context, GLES32.GL_TESS_CONTROL_SHADER, tcs)
        if (tes != null) shaders += attach(context, GLES32.GL_TESS_EVALUATION_SHADER, tes)
        if (gs != null) shaders += attach(context, GLES32.GL_GEOMETRY_SHADER, gs)
        shaders += attach(context, GLES32.GL_FRAGMENT_SHADER, fs)
        GLES32.glLinkProgram(id)
        val status = IntArray(1)
        GLES32.glGetProgramiv(id, GLES32.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) Log.e(TAG, "Link failed: " + GLES32.glGetProgramInfoLog(id))
        shaders.forEach { GLES32.glDeleteShader(it) }
    }

    private fun attach(context: Context, type: Int, asset: String): Int {
        val src = context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val s = GLES32.glCreateShader(type)
        GLES32.glShaderSource(s, src)
        GLES32.glCompileShader(s)
        val status = IntArray(1)
        GLES32.glGetShaderiv(s, GLES32.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) Log.e(TAG, "Compile $asset failed: " + GLES32.glGetShaderInfoLog(s))
        GLES32.glAttachShader(id, s)
        return s
    }

    fun use() = GLES32.glUseProgram(id)

    fun uniform(name: String): Int = cache.getOrPut(name) { GLES32.glGetUniformLocation(id, name) }
    fun attrib(name: String): Int = cache.getOrPut("@$name") { GLES32.glGetAttribLocation(id, name) }
    fun uniformAt(name: String, i: Int) = uniform("$name[$i]")
}

/**
 * OpenGL-ES-3.2-Renderer. Entspricht c/l (+ c/a, c/b, c/c) des Originals.
 * Ausgeführt auf dem GL-Thread; Zustandsänderungen kommen vom Hauptthread (synchronisiert über [lock]).
 */
class LoopsRenderer(private val context: Context, palette: LoopPalette) : GLSurfaceView.Renderer {

    val lock = Any()
    val loops = arrayOfNulls<Loop>(6)
    private val shared = LoopShared()

    /** Wird true, sobald die Oberfläche eine Größe hat (Original: l.b). */
    @Volatile
    var ready = false
        private set

    val camera = FloatArray(3)          // c
    private val vp = FloatArray(16)     // d
    private val proj = FloatArray(16)   // e
    private val view = FloatArray(16)   // f
    private val invView = FloatArray(16) // g

    private var uploadBuffers = true    // h
    private var vpDirty = false         // i
    private var touchDirty = false      // w
    private val touchNow = FloatArray(3)
    private val touchPrev = FloatArray(3)

    private var orientation = 0         // q
    private var nextSlot = 0            // o
    private var minWidth = 0.05f        // r
    private var colors = LoopColors(palette)

    // Hintergrund-Verlauf (c/a + c/c)
    private var gradFrom: GradState = GradState.EMPTY
    private var gradTo: GradState = GradState.EMPTY
    private var gradDirty = false
    private val gradAnim = Anim().apply { interpolator = LinearInterpolator() }
    private var gradPhase = 1f
    private var bgOrientation = 0

    private var circleProg: GlProgram? = null
    private var bgProg: GlProgram? = null
    private val circleBuffers = IntArray(2)
    private val bgBuffers = IntArray(2)

    var requestRender: () -> Unit = {}

    init {
        val dm = context.resources.displayMetrics
        val side = min(dm.widthPixels, dm.heightPixels).coerceAtLeast(1)
        minWidth = (1080f / side).pow(2) * 0.05f
        camera[0] = 0f; camera[1] = 0f; camera[2] = 0f
        recolor()
    }

    // ------------------------------------------------------------------ Zustand / API für Ereignisse

    fun setPalette(p: LoopPalette) {
        synchronized(lock) { colors = LoopColors(p) }
    }

    val palette: LoopPalette get() = colors.palette

    fun hasCircles(): Boolean = synchronized(lock) { loops.any { it != null } }

    private fun rnd(a: Float, b: Float): Float = ((Math.random() * (b - a)) + a).toFloat()

    /** Alle Schleifen in Normalfarben (Original: l.c()). */
    fun recolor() {
        synchronized(lock) {
            for (l in loops) if (l != null) {
                val n = Random.nextInt(100)
                l.setColor(0, colors.normal(n), false)
                l.setColor(1, colors.normal(n), false)
            }
        }
    }

    /** Farben abhängig vom Akkustand (Original: l.a(float)). */
    fun recolor(level: Float) {
        synchronized(lock) {
            for (l in loops) if (l != null) {
                val n = Random.nextInt(100)
                l.setColor(0, colors.forLevel(n, level), false)
                l.setColor(1, colors.forLevel(n, level), false)
            }
        }
    }

    /** Neue Schleife (Original: l.a(float,float)). */
    fun addCircle(cx: Float, cy: Float) {
        var x = rnd(cx, cx) + camera[0]
        var y = rnd(cy, cy)
        val size = rnd(1.5f, 1.6f)
        val width = rnd(max(0.05f, minWidth), 0.16f)
        if (orientation == 1 || orientation == 3) {
            x = (x * 1.7777777777777777).toFloat()
            y = (y * 1.7777777777777777).toFloat()
        }
        val loop = Loop(shared, x, y, size, width, nextSlot)
        val n = Random.nextInt(100)
        loop.setColor(0, colors.normal(n), true)
        loop.setColor(1, colors.normal(n), true)
        loop.moveTo(x, y, size, 0.1f, 1_000_000_000L, 0)
        synchronized(lock) {
            for (o in loops) if (o != null) {
                o.depth += 0.3f
                o.moveTo(o.target.pos[0], o.target.pos[1], o.target.scale, 0.3f, 1_000_000_000L, 0)
            }
            if (loops[nextSlot] != null) {
                loops[nextSlot] = null
                uploadBuffers = true
            }
            loops[nextSlot] = loop
            nextSlot = if (nextSlot == 5) 0 else nextSlot + 1
            loops[nextSlot]?.fadeOut()
        }
        uploadBuffers = true
    }

    /** Berührung (Original: l.a(f,f2,f3)). */
    fun setTouch(x: Float, y: Float, z: Float) {
        if (!ready) return
        touchPrev[0] = touchNow[0]; touchPrev[1] = touchNow[1]; touchPrev[2] = touchNow[2]
        if (orientation == 1 || orientation == 3) {
            touchNow[0] = x * 1.777777f
            touchNow[1] = y
        } else {
            touchNow[0] = x
            touchNow[1] = y * 1.777777f
        }
        touchNow[2] = z
        touchDirty = true
    }

    /** Schleifen von (x0,y0) nach (x1,y1) (Original: l.a mit 9 Parametern). */
    fun moveFrom(x0: Float, y0: Float, x1: Float, y1: Float, minS: Float, maxS: Float, shape: Float, durNs: Long, interp: Int) {
        synchronized(lock) {
            for (l in loops) if (l != null) {
                if (!l.fading) {
                    synchronized(l.lock) {
                        l.start.pos = floatArrayOf(x0, y0, l.depth, 0f)
                        l.current.pos = floatArrayOf(x0, y0, l.depth, 0f)
                        l.target.pos = floatArrayOf(x0, y0, l.depth, 0f)
                        l.start.scale = 0f; l.current.scale = 0f; l.target.scale = 0f
                    }
                }
                l.moveTo(x1, y1, rnd(minS, maxS), shape, rnd(durNs * 0.7f, durNs.toFloat()).toLong(), interp)
            }
        }
        uploadBuffers = true
    }

    /** Schleifen zu (x,y) bewegen (Original: l.a mit 8 Parametern). */
    fun move(x: Float, y: Float, v: Float, minS: Float, maxS: Float, shape: Float, durNs: Long, interp: Int) {
        synchronized(lock) {
            for (l in loops) if (l != null) {
                rnd(x - v, x + v); rnd(y - v, y + v) // Original verbraucht hier Zufallswerte, Ergebnis unbenutzt
                l.moveTo(x, y, rnd(minS, maxS), shape, rnd(durNs * 0.7f, durNs.toFloat()).toLong(), interp)
            }
        }
        uploadBuffers = true
    }

    fun move(x: Float, y: Float, minS: Float, maxS: Float, shape: Float, durNs: Long, interp: Int) =
        move(x, y, 0f, minS, maxS, shape, durNs, interp)

    /** Wisch-Versatz (Original: Fling-Ereignis). */
    fun offsetCircles(dx: Float, dy: Float) {
        synchronized(lock) {
            for (l in loops) if (l != null) {
                val delay = (Math.random() * 0.1 * 1.0E9).toLong()
                l.offsetBy(dx, dy, -2f + camera[0] * 2f, 2f + camera[0] * 2f, delay, 1_000_000_000L - delay)
            }
        }
        uploadBuffers = true
    }

    /** Kamera-Versatz durch Homescreen-Seiten (Original: v.onOffsetsChanged). */
    fun setScroll(offset: Float) {
        synchronized(lock) {
            camera[0] = offset - 0.5f
            camera[1] = 0f
            camera[2] = 0f
            Matrix.setLookAtM(view, 0, -(camera[0] * 1.5f), 0f, 0f, -(camera[0] * 1.5f), 0f, 13f, 0f, 1f, 0f)
            Matrix.invertM(invView, 0, view, 0)
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        }
        vpDirty = true
    }

    private fun startGradient(g: Gradient?, fallback: Gradient?, durNs: Long) {
        val src = g ?: fallback ?: return
        synchronized(lock) {
            gradFrom = gradTo
            gradTo = GradState.from(src)
            gradDirty = true
            gradAnim.start(durNs)
        }
    }

    /** Sperrbildschirm-Verlauf (Original: l.a(long)). */
    fun lockGradient(durNs: Long) = startGradient(palette.lock, palette.home, durNs)

    /** Homescreen-Verlauf (Original: l.b(long)). */
    fun homeGradient(durNs: Long) = startGradient(palette.home, palette.lock, durNs)

    /** Akku-/Lade-Verlauf (Original: l.a(long,float)). */
    fun levelGradient(durNs: Long, level: Float) {
        val p = palette
        val g = if (level >= 0.16f || p.batteryLow == null) (p.charging ?: p.home) else p.batteryLow
        startGradient(g, p.home, durNs)
    }

    // ------------------------------------------------------------------ GLSurfaceView.Renderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.i(TAG, "GL_VERSION=" + GLES32.glGetString(GLES32.GL_VERSION))
        GLES32.glClearColor(0f, 0f, 0f, 1f)
        GLES32.glEnable(GLES32.GL_BLEND)
        GLES32.glBlendFunc(GLES32.GL_SRC_ALPHA, GLES32.GL_ONE_MINUS_SRC_ALPHA)

        val cp = GlProgram(context, "circle_vs.glsl", "circle_tcs.glsl", "circle_tes.glsl", "circle_gs.glsl", "circle_fs.glsl")
        circleProg = cp
        GLES32.glGenBuffers(2, circleBuffers, 0)

        Matrix.setLookAtM(view, 0, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.invertM(invView, 0, view, 0)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        val bp = GlProgram(context, "background_vs.glsl", null, null, null, "background_fs.glsl")
        bgProg = bp
        GLES32.glGenBuffers(2, bgBuffers, 0)
        val vb: FloatBuffer = ByteBuffer.allocateDirect(BG_VERTS.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(BG_VERTS).also { it.position(0) }
        val ib: ShortBuffer = ByteBuffer.allocateDirect(BG_INDEX.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().put(BG_INDEX).also { it.position(0) }
        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, bgBuffers[0])
        GLES32.glBufferData(GLES32.GL_ARRAY_BUFFER, BG_VERTS.size * 4, vb, GLES32.GL_STATIC_DRAW)
        GLES32.glBindBuffer(GLES32.GL_ELEMENT_ARRAY_BUFFER, bgBuffers[1])
        GLES32.glBufferData(GLES32.GL_ELEMENT_ARRAY_BUFFER, BG_INDEX.size * 2, ib, GLES32.GL_STATIC_DRAW)
        gradPhase = 1f

        // Nach (Wieder-)Erzeugen des GL-Kontexts alles neu übertragen.
        uploadBuffers = true
        vpDirty = true
        touchDirty = true
        gradDirty = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        val cp = circleProg ?: return
        val bp = bgProg ?: return
        cp.use()
        GLES32.glViewport(0, 0, width, height)

        val newOrientation = Orientation.update(context, height > width)
        if (height > width) {
            val f = height.toFloat() / width
            Matrix.frustumM(proj, 0, -1f, 1f, -f, f, 10f, 40f)
        } else {
            val f = width.toFloat() / height
            Matrix.frustumM(proj, 0, -f, f, -1f, 1f, 10f, 40f)
        }
        synchronized(lock) {
            Matrix.setLookAtM(view, 0, -(camera[0] * 1.5f), 0f, 0f, -(camera[0] * 1.5f), 0f, 13f, 0f, 1f, 0f)
            Matrix.invertM(invView, 0, view, 0)
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        }
        vpDirty = true

        bp.use()
        GLES32.glViewport(0, 0, width, height)
        GLES32.glUniform2f(bp.uniform("uScreenSize"), width.toFloat(), height.toFloat())
        bgOrientation = newOrientation
        gradDirty = true
        ready = true

        // vorhandene Schleifen an neue Drehung anpassen
        synchronized(lock) {
            for (l in loops) if (l != null) {
                val a = floatArrayOf(l.target.pos[0], l.target.pos[1])
                val b = floatArrayOf(l.current.pos[0], l.current.pos[1])
                synchronized(l.lock) {
                    when (orientation - newOrientation) {
                        -3, 1 -> rotate(l, a, b, swap = true, negFirst = false)
                        -2, 2 -> {
                            l.target.pos[0] = a[0] * -1f; l.target.pos[1] = a[1] * -1f
                            l.current.pos[0] = b[0] * -1f; l.current.pos[1] = b[1] * -1f
                        }
                        -1, 3 -> rotate(l, a, b, swap = true, negFirst = true)
                    }
                }
            }
        }
        orientation = newOrientation
        requestRender()
    }

    /** swap: x'=y, y'=∓x. negFirst=true: x'=-y, y'=x. */
    private fun rotate(l: Loop, a: FloatArray, b: FloatArray, swap: Boolean, negFirst: Boolean) {
        if (!swap) return
        if (negFirst) {
            l.target.pos[0] = a[1] * -1f; l.target.pos[1] = a[0]
            l.current.pos[0] = b[1] * -1f; l.current.pos[1] = b[0]
        } else {
            l.target.pos[0] = a[1]; l.target.pos[1] = a[0] * -1f
            l.current.pos[0] = b[1]; l.current.pos[1] = b[0] * -1f
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        drawBackground()
        drawLoops()
    }

    private fun setColor(loc: Int, c: Int) =
        GLES32.glUniform4f(loc, Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f, Color.alpha(c) / 255f)

    private fun drawBackground() {
        val p = bgProg ?: return
        p.use()
        gradAnim.update()
        if (gradAnim.active) gradPhase = gradAnim.value()
        if (gradDirty) {
            val from = gradFrom
            val to = gradTo
            for (i in 0 until 5) {
                setColor(p.uniform("uGradColor${i}A"), from.colors[i])
                setColor(p.uniform("uGradColor${i}B"), to.colors[i])
            }
            for (i in 0 until 3) {
                GLES32.glUniform1f(p.uniform("uGradP${i + 1}A"), from.points[i])
                GLES32.glUniform1f(p.uniform("uGradP${i + 1}B"), to.points[i])
            }
            gradDirty = false
        }
        GLES32.glUniform1i(p.uniform("uOrientation"), bgOrientation)
        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, bgBuffers[0])
        GLES32.glBindBuffer(GLES32.GL_ELEMENT_ARRAY_BUFFER, bgBuffers[1])
        val pos = p.attrib("inPosition")
        GLES32.glEnableVertexAttribArray(pos)
        GLES32.glVertexAttribPointer(pos, 2, GLES32.GL_FLOAT, false, 0, 0)
        GLES32.glUniform1f(p.uniform("uGradAnimationPhase"), gradPhase)
        GLES32.glDrawElements(GLES32.GL_TRIANGLE_STRIP, BG_INDEX.size, GLES32.GL_UNSIGNED_SHORT, 0)
        GLES32.glDisableVertexAttribArray(pos)
    }

    private fun drawLoops() {
        val p = circleProg ?: return
        p.use()
        if (touchDirty) {
            touchDirty = false
            GLES32.glUniform4f(p.uniform("uTouchPos"), touchNow[0], touchNow[1], touchNow[2], 0f)
        }
        if (vpDirty) {
            vpDirty = false
            GLES32.glUniformMatrix4fv(p.uniform("uVPMatrix"), 1, false, vp, 0)
        }
        synchronized(lock) {
            for (l in loops) {
                if (l == null) continue
                if (l.applyAnimation()) {
                    GLES32.glUniform1f(p.uniformAt("uAnimPhase", l.slot), l.lastPhase)
                }
                val moved = FloatArray(4)
                synchronized(l.lock) {
                    Matrix.multiplyMV(moved, 0, invView, 0, l.current.pos, 0)
                    Matrix.setIdentityM(l.matrix, 0)
                    Matrix.translateM(l.matrix, 0, moved[0], moved[1], moved[2] + 13f)
                    val rot = FloatArray(16)
                    Matrix.setRotateEulerM(
                        rot, 0,
                        Math.toDegrees(l.current.rot[0].toDouble()).toFloat(),
                        Math.toDegrees(l.current.rot[1].toDouble()).toFloat(),
                        Math.toDegrees(l.current.rot[2].toDouble()).toFloat(),
                    )
                    Matrix.multiplyMM(l.matrix, 0, l.matrix, 0, rot, 0)
                    Matrix.scaleM(l.matrix, 0, l.current.scale, l.current.scale, 1f)
                }
                val c0 = l.current.color[0]
                val c1 = l.current.color[1]
                val a = l.current.alpha
                GLES32.glUniform4f(p.uniformAt("uColorA", l.slot), c0[0], c0[1], c0[2], a)
                GLES32.glUniform4f(p.uniformAt("uColorB", l.slot), c1[0], c1[1], c1[2], a)
                GLES32.glUniformMatrix4fv(p.uniformAt("uTransM", l.slot), 1, false, l.matrix, 0)
                GLES32.glUniform1f(p.uniformAt("uWidth", l.slot), l.width * min(l.sizeLimit / l.current.scale, 1f))
            }
        }
        if (uploadBuffers) {
            uploadBuffers = false
            val fb = ByteBuffer.allocateDirect(shared.vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            synchronized(lock) {
                fb.put(shared.vertices).position(0)
            }
            GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, circleBuffers[0])
            GLES32.glBufferData(GLES32.GL_ARRAY_BUFFER, shared.vertices.size * 4, fb, GLES32.GL_DYNAMIC_DRAW)
            val ib = ByteBuffer.allocateDirect(shared.indices.size).order(ByteOrder.nativeOrder())
            synchronized(lock) {
                ib.put(shared.indices).position(0)
            }
            GLES32.glBindBuffer(GLES32.GL_ELEMENT_ARRAY_BUFFER, circleBuffers[1])
            GLES32.glBufferData(GLES32.GL_ELEMENT_ARRAY_BUFFER, shared.indices.size, ib, GLES32.GL_DYNAMIC_DRAW)
        }
        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, circleBuffers[0])
        val pos = p.attrib("inPosition")
        GLES32.glEnableVertexAttribArray(pos)
        GLES32.glVertexAttribPointer(pos, 2, GLES32.GL_FLOAT, false, 0, 0)
        GLES32.glPatchParameteri(GLES32.GL_PATCH_VERTICES, 8)
        GLES32.glBindBuffer(GLES32.GL_ELEMENT_ARRAY_BUFFER, circleBuffers[1])
        GLES32.glDrawElements(GLES32.GL_PATCHES, 192, GLES32.GL_UNSIGNED_BYTE, 0)
        GLES32.glDisableVertexAttribArray(pos)
    }

    companion object {
        private val BG_VERTS = floatArrayOf(-1f, -1f, -1f, 1f, 1f, -1f, 1f, 1f)
        private val BG_INDEX = shortArrayOf(0, 1, 2, 3)
    }
}
