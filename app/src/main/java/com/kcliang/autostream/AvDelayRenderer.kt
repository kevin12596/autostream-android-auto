package com.kcliang.autostream

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/**
 * A/V-sync renderer: VirtualDisplay -> [inputSurface] (SurfaceTexture, OES) -> ring buffer of FBO
 * textures (timestamped) -> car Surface (EGL window surface). Draws the newest frame whose
 * timestamp <= now - delay. delay == 0 is passthrough (latest frame drawn immediately).
 *
 * The output (car Surface) can be detached/reattached at will; the SurfaceTexture keeps being
 * consumed (updateTexImage) so the VirtualDisplay/WebView never stall while detached.
 *
 * All GL state lives on a dedicated HandlerThread. Public methods may be called from any thread.
 */
class AvDelayRenderer {
    private class Slot(val tex: Int, val fbo: Int) {
        var ts = 0L
        var seq = 0L
        var valid = false
    }

    private class Program(val id: Int, val aPos: Int, val aTex: Int, val uTexM: Int, val uTex: Int)

    private val thread = HandlerThread("AvDelayGL")
    private var handler: Handler? = null

    @Volatile
    var inputSurface: Surface? = null
        private set

    @Volatile
    private var delayMs = 0

    @Volatile
    private var released = false

    // ---------------- GL-thread-only state ----------------
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var idleSurface: EGLSurface = EGL14.EGL_NO_SURFACE // 1x1 pbuffer (or NO_SURFACE if surfaceless)
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var windowCurrent = false
    private var outW = 0
    private var outH = 0
    private var inW = 0
    private var inH = 0

    private var surfaceTexture: SurfaceTexture? = null
    private var oesTex = 0
    private val texMatrix = FloatArray(16)
    private val identity = FloatArray(16)
    private var progOes: Program? = null
    private var prog2d: Program? = null

    private var hasFrame = false
    private var oesTs = 0L // uptime ms when the current OES content arrived
    private var lastCapturedOesTs = -1L
    private var lastCaptureMs = 0L
    private var captureIntervalMs = BASE_INTERVAL_MS
    private var capturePending = false

    private val slots = ArrayList<Slot>()
    private var ringW = 0
    private var ringH = 0
    private var ringAllocatedFor = 0
    private var writeIdx = 0
    private var seqCounter = 0L
    private var lastDrawnSeq = -1L

    private val quadPos: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val quadTex: FloatBuffer = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    private val captureRunnable = Runnable {
        capturePending = false
        if (canDrawVideo() && delayMs > 0 && hasFrame) captureNow()
    }
    private val drawDueRunnable = Runnable { drawDueFrame() }

    // ------------------------------------------------------------------ public API

    /** Initialises EGL/GL + the input SurfaceTexture. @return false on failure (caller falls back). */
    fun start(width: Int, height: Int): Boolean {
        thread.start()
        handler = Handler(thread.looper)
        val ok = runSync(false) { initGl(width, height); true }
        if (!ok) {
            Log.e(TAG, "GL init failed")
            release()
        }
        return ok
    }

    /** Attaches the car Surface as the EGL output. @return false if the EGL surface can't be created. */
    fun attachOutput(surface: Surface, width: Int, height: Int): Boolean = runSync(false) {
        destroyWindow()
        val s = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (s == null || s == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
            false
        } else {
            window = s
            outW = width
            outH = height
            if (!makeWindowCurrent()) {
                destroyWindow()
                false
            } else {
                lastDrawnSeq = -1
                redrawNow()
                true
            }
        }
    }

    /** Drops the EGL window surface; frames keep being consumed but nothing is drawn. */
    fun detachOutput() {
        runSync(Unit) {
            destroyWindow()
            invalidateRing()
        }
    }

    fun setDelay(ms: Int) {
        val v = ms.coerceIn(0, WebViewConfig.AV_DELAY_MAX_MS)
        delayMs = v
        handler?.post { onDelayChanged() }
    }

    fun release() {
        if (released) return
        released = true
        if (handler != null && thread.isAlive) {
            runSync(Unit, 1500) { releaseGl() }
            thread.quitSafely()
        } else if (thread.isAlive) {
            thread.quitSafely()
        }
    }

    // ------------------------------------------------------------------ GL thread

    private fun initGl(width: Int, height: Int) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val ver = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) { "eglInitialize: ${eglErr()}" }
        eglConfig = chooseConfig() ?: error("no suitable EGLConfig")
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext: ${eglErr()}" }
        idleSurface = try {
            EGL14.eglCreatePbufferSurface(
                eglDisplay, eglConfig,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            ) ?: EGL14.EGL_NO_SURFACE
        } catch (t: Throwable) {
            EGL14.EGL_NO_SURFACE
        }
        // NO_SURFACE here relies on EGL_KHR_surfaceless_context (universal on modern Android).
        check(makeIdleCurrent()) { "eglMakeCurrent(idle): ${eglErr()}" }

        progOes = buildProgram(VS, FS_OES)
        prog2d = buildProgram(VS, FS_2D)
        Matrix.setIdentityM(identity, 0)
        Matrix.setIdentityM(texMatrix, 0)

        oesTex = genTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        val st = SurfaceTexture(oesTex)
        st.setDefaultBufferSize(width, height)
        st.setOnFrameAvailableListener({ onFrameAvailable() }, handler)
        surfaceTexture = st
        inW = width
        inH = height
        inputSurface = Surface(st)
        checkGl("initGl")
        Log.i(TAG, "GL pipeline ready ${width}x$height (EGL ${ver[0]}.${ver[1]})")
    }

    private fun chooseConfig(): EGLConfig? {
        val base = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
        )
        val variants = listOf(
            intArrayOf(EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT, EGL_RECORDABLE_ANDROID, 1),
            intArrayOf(EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL_RECORDABLE_ANDROID, 1),
            intArrayOf(EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT),
            intArrayOf(EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT),
        )
        for (v in variants) {
            val attrs = base + v + intArrayOf(EGL14.EGL_NONE)
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (EGL14.eglChooseConfig(eglDisplay, attrs, 0, configs, 0, 1, num, 0) && num[0] > 0 && configs[0] != null) {
                return configs[0]
            }
        }
        return null
    }

    private fun onFrameAvailable() {
        if (released) return
        val st = surfaceTexture ?: return
        try {
            // The context is always current on this thread (window or idle surface).
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
        } catch (t: Throwable) {
            Log.w(TAG, "updateTexImage failed", t)
            return
        }
        oesTs = SystemClock.uptimeMillis()
        hasFrame = true
        if (!canDrawVideo()) return
        if (delayMs <= 0) drawLatestToWindow() else requestCapture()
    }

    private fun canDrawVideo(): Boolean = !released && window != EGL14.EGL_NO_SURFACE

    private fun onDelayChanged() {
        val h = handler ?: return
        if (delayMs <= 0) {
            h.removeCallbacks(captureRunnable)
            h.removeCallbacks(drawDueRunnable)
            capturePending = false
            releaseRing()
            if (canDrawVideo() && hasFrame) drawLatestToWindow()
        } else {
            ensureRing()
            lastCapturedOesTs = -1
            if (canDrawVideo() && hasFrame) requestCapture()
            h.post(drawDueRunnable)
        }
    }

    /** Immediate redraw on attach / resize. */
    private fun redrawNow() {
        if (window == EGL14.EGL_NO_SURFACE) return
        if (!hasFrame) {
            drawBlack(); return
        }
        invalidateRing()
        drawLatestToWindow()
        if (delayMs > 0) {
            lastCapturedOesTs = -1
            requestCapture()
        }
    }

    private fun requestCapture() {
        val h = handler ?: return
        val now = SystemClock.uptimeMillis()
        val next = lastCaptureMs + captureIntervalMs
        if (now >= next) {
            h.removeCallbacks(captureRunnable)
            capturePending = false
            captureNow()
        } else if (!capturePending) {
            // Deferred capture guarantees the last frame of a burst is never dropped by throttling.
            capturePending = true
            h.postAtTime(captureRunnable, next)
        }
    }

    private fun captureNow() {
        if (oesTs == lastCapturedOesTs) return
        ensureRing()
        if (slots.isEmpty()) {
            drawLatestToWindow(); return
        }
        val s = slots[writeIdx]
        writeIdx = (writeIdx + 1) % slots.size
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, s.fbo)
        GLES20.glViewport(0, 0, ringW, ringH)
        drawQuad(progOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex, texMatrix)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        s.ts = oesTs
        s.seq = ++seqCounter
        s.valid = true
        lastCapturedOesTs = oesTs
        lastCaptureMs = SystemClock.uptimeMillis()
        handler?.postAtTime(drawDueRunnable, s.ts + delayMs)
    }

    private fun drawDueFrame() {
        val d = delayMs
        if (!canDrawVideo() || d <= 0) return
        val target = SystemClock.uptimeMillis() - d
        var best: Slot? = null
        for (s in slots) {
            if (s.valid && s.ts <= target && (best == null || s.ts > best.ts)) best = s
        }
        val b = best ?: return
        if (b.seq == lastDrawnSeq) return
        if (!makeWindowCurrent()) return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, outW, outH)
        drawQuad(prog2d, GLES20.GL_TEXTURE_2D, b.tex, identity)
        swap()
        lastDrawnSeq = b.seq
    }

    private fun drawLatestToWindow() {
        if (!canDrawVideo() || !hasFrame) return
        if (!makeWindowCurrent()) return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, outW, outH)
        drawQuad(progOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex, texMatrix)
        swap()
    }

    private fun drawBlack() {
        if (window == EGL14.EGL_NO_SURFACE || !makeWindowCurrent()) return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, outW, outH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        swap()
    }

    // ---------------- ring buffer

    private fun ensureRing() {
        val d = delayMs
        if (d <= 0 || inW <= 0 || inH <= 0) return
        val frameBytes = inW.toLong() * inH * 4
        val maxFrames = (RING_BUDGET_BYTES / frameBytes).toInt().coerceIn(MIN_FRAMES, MAX_FRAMES)
        var interval = BASE_INTERVAL_MS
        var needed = ceil(d.toDouble() / interval).toInt() + SLACK_FRAMES
        if (needed > maxFrames) {
            interval = ceil(d.toDouble() / (maxFrames - SLACK_FRAMES).coerceAtLeast(1)).toLong()
            needed = maxFrames
        }
        captureIntervalMs = interval
        if (ringAllocatedFor >= needed && ringW == inW && ringH == inH) return
        releaseRing()
        for (i in 0 until needed) {
            val tex = genTexture(GLES20.GL_TEXTURE_2D)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, inW, inH, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
            )
            val fb = IntArray(1)
            GLES20.glGenFramebuffers(1, fb, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb[0])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0,
            )
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            val err = GLES20.glGetError()
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE || err != GLES20.GL_NO_ERROR) {
                Log.w(TAG, "ring alloc stopped at $i/$needed (status=$status err=$err)")
                GLES20.glDeleteFramebuffers(1, fb, 0)
                GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
                break
            }
            slots.add(Slot(tex, fb[0]))
        }
        ringW = inW
        ringH = inH
        ringAllocatedFor = needed // even if partial: don't retry every frame
        writeIdx = 0
        Log.i(TAG, "ring: ${slots.size} frames @ ${captureIntervalMs}ms for delay ${d}ms")
    }

    private fun invalidateRing() {
        for (s in slots) s.valid = false
        lastDrawnSeq = -1
    }

    private fun releaseRing() {
        for (s in slots) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(s.fbo), 0)
            GLES20.glDeleteTextures(1, intArrayOf(s.tex), 0)
        }
        slots.clear()
        ringAllocatedFor = 0
        writeIdx = 0
        lastDrawnSeq = -1
    }

    // ---------------- EGL helpers

    private fun makeIdleCurrent(): Boolean {
        val ok = EGL14.eglMakeCurrent(eglDisplay, idleSurface, idleSurface, eglContext)
        if (ok) windowCurrent = false
        return ok
    }

    private fun makeWindowCurrent(): Boolean {
        if (window == EGL14.EGL_NO_SURFACE) return false
        if (windowCurrent) return true
        val ok = EGL14.eglMakeCurrent(eglDisplay, window, window, eglContext)
        if (!ok) Log.w(TAG, "eglMakeCurrent(window): ${eglErr()}")
        windowCurrent = ok
        return ok
    }

    private fun destroyWindow() {
        if (window == EGL14.EGL_NO_SURFACE) return
        makeIdleCurrent()
        EGL14.eglDestroySurface(eglDisplay, window)
        window = EGL14.EGL_NO_SURFACE
        windowCurrent = false
    }

    private fun swap() {
        if (!EGL14.eglSwapBuffers(eglDisplay, window)) {
            val e = EGL14.eglGetError()
            Log.w(TAG, "eglSwapBuffers failed 0x${Integer.toHexString(e)}")
            if (e == EGL14.EGL_BAD_SURFACE || e == EGL14.EGL_BAD_NATIVE_WINDOW) destroyWindow()
        }
    }

    private fun releaseGl() {
        handler?.removeCallbacksAndMessages(null)
        try {
            surfaceTexture?.setOnFrameAvailableListener(null)
            if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT) {
                destroyWindow()
                makeIdleCurrent()
                releaseRing()
                if (oesTex != 0) GLES20.glDeleteTextures(1, intArrayOf(oesTex), 0)
                progOes?.let { GLES20.glDeleteProgram(it.id) }
                prog2d?.let { GLES20.glDeleteProgram(it.id) }
            }
            oesTex = 0
            surfaceTexture?.release()
            surfaceTexture = null
            inputSurface?.release()
            inputSurface = null
        } catch (t: Throwable) {
            Log.w(TAG, "GL resource release failed", t)
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (idleSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, idleSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            // Intentionally no eglTerminate: the default display is shared in-process (WebView/HWUI).
        }
        idleSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
        Log.i(TAG, "GL pipeline released")
    }

    // ---------------- GL helpers

    private fun drawQuad(prog: Program?, target: Int, tex: Int, m: FloatArray) {
        val p = prog ?: return
        GLES20.glUseProgram(p.id)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, tex)
        GLES20.glUniform1i(p.uTex, 0)
        GLES20.glUniformMatrix4fv(p.uTexM, 1, false, m, 0)
        quadPos.position(0)
        GLES20.glVertexAttribPointer(p.aPos, 2, GLES20.GL_FLOAT, false, 0, quadPos)
        GLES20.glEnableVertexAttribArray(p.aPos)
        quadTex.position(0)
        GLES20.glVertexAttribPointer(p.aTex, 2, GLES20.GL_FLOAT, false, 0, quadTex)
        GLES20.glEnableVertexAttribArray(p.aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(p.aPos)
        GLES20.glDisableVertexAttribArray(p.aTex)
        GLES20.glBindTexture(target, 0)
    }

    private fun genTexture(target: Int): Int {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(target, t[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    private fun buildProgram(vs: String, fs: String): Program {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val st = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, st, 0)
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        if (st[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            error("link failed: $log")
        }
        return Program(
            p,
            GLES20.glGetAttribLocation(p, "aPos"),
            GLES20.glGetAttribLocation(p, "aTex"),
            GLES20.glGetUniformLocation(p, "uTexM"),
            GLES20.glGetUniformLocation(p, "uTex"),
        )
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val st = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, st, 0)
        if (st[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            error("shader compile failed: $log")
        }
        return s
    }

    private fun checkGl(op: String) {
        val e = GLES20.glGetError()
        check(e == GLES20.GL_NO_ERROR) { "$op: glError 0x${Integer.toHexString(e)}" }
    }

    private fun eglErr(): String = "0x" + Integer.toHexString(EGL14.eglGetError())

    private fun <T> runSync(default: T, timeoutMs: Long = 2000, block: () -> T): T {
        val h = handler ?: return default
        if (Looper.myLooper() == h.looper) {
            return try {
                block()
            } catch (t: Throwable) {
                Log.e(TAG, "GL op failed", t); default
            }
        }
        val latch = CountDownLatch(1)
        var result = default
        val posted = h.post {
            try {
                result = block()
            } catch (t: Throwable) {
                Log.e(TAG, "GL op failed", t)
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return default
        return try {
            if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) result else {
                Log.w(TAG, "GL op timed out after ${timeoutMs}ms"); default
            }
        } catch (_: InterruptedException) {
            default
        }
    }

    companion object {
        private const val TAG = "AvDelayRenderer"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val BASE_INTERVAL_MS = 33L // ~30 fps capture while delaying
        private const val RING_BUDGET_BYTES = 192L * 1024 * 1024
        private const val MIN_FRAMES = 4
        private const val MAX_FRAMES = 40
        private const val SLACK_FRAMES = 3

        private fun floatBuffer(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(a); position(0)
            }

        private const val VS = """
            attribute vec4 aPos;
            attribute vec2 aTex;
            uniform mat4 uTexM;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uTexM * vec4(aTex, 0.0, 1.0)).xy;
            }
        """

        private const val FS_OES = """#extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES uTex;
            void main() {
                gl_FragColor = vec4(texture2D(uTex, vTex).rgb, 1.0);
            }
        """

        private const val FS_2D = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D uTex;
            void main() {
                gl_FragColor = vec4(texture2D(uTex, vTex).rgb, 1.0);
            }
        """
    }
}
