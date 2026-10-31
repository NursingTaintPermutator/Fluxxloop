package com.example.fluxxloop

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.PointF
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.animation.PathInterpolator
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Live Wallpaper "Xperia Loops" – Nachbau des Verhaltens der Original-APK.
 * Voraussetzung: OpenGL ES 3.2 (Tessellation- und Geometry-Shader), minSdk 26.
 */
class LoopsWallpaperService : WallpaperService() {

    /** Gerätegeschützter Speicher: auch vor dem ersten Entsperren nach einem Neustart nutzbar (Direct Boot). */
    private lateinit var bootSafeContext: Context

    override fun onCreate() {
        bootSafeContext = createDeviceProtectedStorageContext()
        super.onCreate()
    }

    override fun onCreateEngine(): Engine = LoopsEngine()

    inner class LoopsEngine : Engine(), EventHost, SharedPreferences.OnSharedPreferenceChangeListener {

        private val ctx: Context get() = bootSafeContext
        private val prefs: SharedPreferences = bootSafeContext.getSharedPreferences(PREFS, MODE_PRIVATE)

        private val renderer = LoopsRenderer(ctx, LoopPalettes.get(prefs.getString(KEY_COLOR, LoopPalettes.DEFAULT)))
        private val manager = EventManager(this, EventEnv(renderer, ctx))

        private var glView: GlView? = null
        private var visible = false
        private var paletteChanged = false
        private var surfaceW = 1
        private var surfaceH = 1

        // Seitenversatz (Original: v.onOffsetsChanged)
        private var lastOffset = Float.NaN
        private var jumped = 0

        // Seiten-Position für Ereignisse (Original: v.j)
        private var pane = 0.5f

        // Befehls-Entprellung (Original: Klasse c)
        private var lastCommandAt = System.currentTimeMillis()
        private var lastCommand = ""

        private val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        private val keyguard = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        override var touchEnabled = true
        override val wallpaperVisible: Boolean get() = visible

        private val touch = TouchController()

        private val receiver = object : BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(context: Context, intent: Intent) {
                val level = Battery.level(ctx)
                val charging = manager.has(EventType.PowerConnected)
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        manager.remove(EventType.ScreenOff)
                        if (keyguard.inKeyguardRestrictedInputMode()) {
                            manager.post(ScreenOnEvent(level, pane, charging))
                        } else {
                            manager.post(UserPresentEvent(level, pane, charging))
                        }
                        manager.start()
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        manager.remove(EventType.ScreenOn)
                        if (power.isInteractive) return
                        manager.post(ScreenOffEvent(pane, charging || level < 0.16f))
                        manager.start()
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        manager.remove(EventType.ScreenOff)
                        manager.remove(EventType.ScreenOn)
                        manager.post(UserPresentEvent(level, pane, charging))
                        manager.start()
                    }
                    Intent.ACTION_BATTERY_LOW -> {
                        manager.post(BatteryLowEvent())
                        manager.start()
                    }
                    Intent.ACTION_BATTERY_OKAY -> {
                        manager.remove(EventType.BatteryLow)
                        manager.post(BatteryOkayEvent())
                        manager.start()
                    }
                    Intent.ACTION_POWER_CONNECTED -> {
                        manager.post(PowerConnectedEvent(manager))
                        manager.start()
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        manager.remove(EventType.ScreenOn)
                        manager.remove(EventType.PowerConnected)
                        manager.post(PowerDisconnectedEvent())
                        manager.start()
                    }
                }
            }
        }

        /** GLSurfaceView, das direkt auf die Wallpaper-Oberfläche zeichnet. */
        private inner class GlView(context: Context) : GLSurfaceView(context) {
            override fun getHolder(): SurfaceHolder = surfaceHolder
            fun destroy() = super.onDetachedFromWindow()
        }

        // ---------------------------------------------------------------- Lebenszyklus

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            Orientation.update(ctx, true)

            glView = GlView(this@LoopsWallpaperService).apply {
                setEGLContextClientVersion(3)
                setEGLConfigChooser(8, 8, 8, 8, 0, 0)
                preserveEGLContextOnPause = true
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            }
            renderer.requestRender = { glView?.requestRender() }

            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_BATTERY_OKAY)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                priority = 900
            }
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(receiver, filter)
            }
            prefs.registerOnSharedPreferenceChangeListener(this)

            // Start: Logo-Ereignis, die Schleifen "wachsen" aus der Mitte (Original: WallpaperLaunch)
            manager.post(WallpaperLaunchEvent())
        }

        override fun onDestroy() {
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            try { ctx.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
            manager.clear()
            glView?.destroy()
            glView = null
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceW = width.coerceAtLeast(1)
            surfaceH = height.coerceAtLeast(1)
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            if (isVisible) {
                glView?.onResume()
                if (!visible) {
                    if (paletteChanged) {
                        renderer.setPalette(LoopPalettes.get(prefs.getString(KEY_COLOR, LoopPalettes.DEFAULT)))
                        paletteChanged = false
                        manager.post(WallpaperLaunchEvent())
                    } else if (!renderer.hasCircles()) {
                        manager.post(WallpaperLaunchEvent())
                    }
                    if (!isPreview) {
                        val level = Battery.level(ctx)
                        if (level >= 0.16f) {
                            manager.remove(EventType.BatteryLow)
                        } else if (!manager.has(EventType.BatteryLow)) {
                            manager.post(BatteryLowEvent())
                        }
                        if (Battery.isCharging(ctx) && !manager.has(EventType.PowerConnected)) {
                            manager.post(PowerConnectedEvent(manager))
                        }
                    }
                    manager.start()
                    jumped = 0
                    visible = true
                }
            } else {
                glView?.onPause()
                if (visible) visible = false
            }
            super.onVisibilityChanged(isVisible)
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences, key: String?) {
            if (key != KEY_COLOR) return
            if (!visible) {
                paletteChanged = true
                return
            }
            renderer.setPalette(LoopPalettes.get(sp.getString(KEY_COLOR, LoopPalettes.DEFAULT)))
            manager.post(WallpaperLaunchEvent())
            manager.start()
        }

        // ---------------------------------------------------------------- EventHost

        override fun requestRender() {
            glView?.requestRender()
        }

        override fun onEventEnded(type: EventType) {
            if (type == EventType.BatteryLow) touchEnabled = true
        }

        // ---------------------------------------------------------------- Homescreen-Versatz

        /**
         * Seitenversatz der Schleifen. Sinnvoller Startwert: Nach einem Neustart (Sperrbildschirm,
         * Launcher noch nicht bereit) melden manche Launcher Offset 0 ohne Seitenschritt – dann würden
         * die Schleifen nach links aus dem Bild geschoben. Ohne Seitenschritt gilt deshalb die Mitte (0,5).
         */
        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float, xOffsetStep: Float, yOffsetStep: Float, xPixelOffset: Int, yPixelOffset: Int,
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset)
            val f = if (xOffsetStep <= 0f || xOffsetStep.isNaN() || xOffset.isNaN()) 0.5f else xOffset.coerceIn(0f, 1f)
            if (!lastOffset.isNaN()) {
                if (f - lastOffset > 0.8) {
                    jumped = if (jumped < 0) 0 else 1
                } else if (f - lastOffset < -0.8) {
                    jumped = if (jumped > 0) 0 else -1
                }
            }
            lastOffset = f
            if (jumped == 0) {
                renderer.setScroll(f)
                requestRender()
            }
        }

        // ---------------------------------------------------------------- Befehle (Tippen, Sony-Lockscreen, ...)

        override fun onCommand(action: String, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean): Bundle? {
            if (action == "com.sonymobile.home.wallpaper.command.SWITCH_PAGE" && extras != null) {
                val to = extras.getInt("switch_to_page").toFloat()
                val count = extras.getInt("page_count").toFloat()
                if (count > 0f) pane = (((to + 1f) / count) - 0.5f) * 2f
            }
            val now = System.currentTimeMillis()
            if (action != lastCommand || lastCommandAt + 60 <= now) {
                lastCommand = action
                lastCommandAt = now
                when (action) {
                    "android.wallpaper.tap" -> {
                        manager.post(
                            TapEvent(
                                (((x.toFloat() / surfaceW) * 2f) - 1f) * 1.3f,
                                (((y.toFloat() / surfaceH) * 2f) - 1f) * ((surfaceH * 1.3f) / surfaceW),
                            )
                        )
                        manager.start()
                    }
                    "com.sonymobile.lockscreen.wallpaper.command.FPA_ACQUIRED",
                    "com.sonymobile.lockscreen.wallpaper.command.FPA_ERROR" -> {
                        if (extras == null || extras.getInt("reason") != 6) {
                            manager.post(FpaErrorEvent(pane))
                        } else {
                            manager.post(FpaPeekEvent(pane))
                        }
                        manager.start()
                    }
                    "com.sonymobile.lockscreen.wallpaper.command.UNLOCK",
                    "com.sonymobile.lockscreen.wallpaper.command.FPA_SUCCEEDED" -> {
                        manager.remove(EventType.FpaPeek)
                        manager.remove(EventType.FpaError)
                    }
                    "com.sonymobile.notification.wallpaper.command.NEW" -> {
                        manager.post(NotificationEvent(x.toFloat(), y.toFloat()))
                        manager.start()
                    }
                }
            } else {
                lastCommandAt = now
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        // ---------------------------------------------------------------- Berührung

        override fun onTouchEvent(event: MotionEvent) {
            touch.onTouch(event)
        }

        /** Berührungs-Sog und Wischen (Original: Klassen j und k). */
        private inner class TouchController : Choreographer.FrameCallback {
            private val choreographer = Choreographer.getInstance()
            private val gestures = GestureDetector(
                this@LoopsWallpaperService,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                        if (e1 == null) return false
                        val x = ((velocityX / 32f) + (e2.x - e1.x)) * 0.5625f
                        val y = ((velocityY / 32f) + (e2.y - e1.y)) * 0.75f
                        manager.post(FlingEvent(x / surfaceW, y / surfaceH))
                        manager.start()
                        return true
                    }
                },
            )
            private val pullDown = PointF()      // j
            private val pullUp = PointF()        // i
            private var downAt = 0L              // h
            private var releaseAt = 0L           // m
            private var strength = 0f            // n
            private val release = PathInterpolator(0.0f, 0.0f, 0.2f, 1.0f)
            private val build = PathInterpolator(0.2f, 0.0f, 0.3f, 1.0f)

            private fun dist(x: Float, y: Float): Float =
                sqrt((pullDown.x - x).toDouble().pow(2.0) + (pullDown.y - y).toDouble().pow(2.0)).toFloat()

            fun onTouch(ev: MotionEvent) {
                gestures.onTouchEvent(ev)
                val x = -(((ev.x / surfaceW) * 2f) - 1f)
                val y = -(((ev.y / surfaceH) * 2f) - 1f)
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        choreographer.removeFrameCallback(this)
                        downAt = System.currentTimeMillis()
                        pullDown.set(x, y)
                    }
                    MotionEvent.ACTION_UP -> {
                        val threshold = (150.0f / (dist(x, y) + 1f)).toLong()
                        if (System.currentTimeMillis() - downAt > threshold) {
                            pullUp.set(x, y)
                            releaseAt = System.currentTimeMillis() + threshold
                            choreographer.removeFrameCallback(this)
                            choreographer.postFrameCallback(this)
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        strength = build.getInterpolation(
                            min(1f, ((System.currentTimeMillis() - downAt) - (150.0f / (dist(x, y) + 1f))) / 300f)
                        )
                        if (strength > 0.0) applyTouch(strength, x, y)
                    }
                }
            }

            override fun doFrame(frameTimeNanos: Long) {
                val v = strength - release.getInterpolation((System.currentTimeMillis() - releaseAt) / 900f)
                applyTouch(v, pullUp.x, pullUp.y)
                if (v > 0.01f) choreographer.postFrameCallback(this)
            }

            private fun applyTouch(strength: Float, x: Float, y: Float) {
                if (touchEnabled && strength > 0f) {
                    renderer.setTouch(x, y, strength)
                    if (strength > 0.01f) requestRender()
                }
            }
        }
    }

    companion object {
        const val PREFS = "XLWPPrefs"
        const val KEY_COLOR = "preferences_color"
    }
}
