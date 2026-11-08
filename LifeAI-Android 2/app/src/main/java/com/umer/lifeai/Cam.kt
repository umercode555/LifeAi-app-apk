package com.umer.lifeai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/** White full-screen overlay at max brightness = "front flash" for phones whose front camera has no LED. Needs "Display over other apps". */
object ScreenFlash {
    private var v: View? = null
    fun canUse(c: Context) = Settings.canDrawOverlays(c)
    fun on(c: Context) {
        if (!canUse(c) || v != null) return
        runCatching {
            val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = View(c).apply { setBackgroundColor(Color.WHITE) }
            val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT).apply { screenBrightness = 1f }
            wm.addView(view, lp); v = view
        }
    }
    fun off(c: Context) { v?.let { x -> runCatching { (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(x) } }; v = null }
}

/** One-shot silent camera capture (no preview). Must be called on the main thread. */
object Cam {
    /** [flash]: real LED/torch when the lens has one, otherwise white-screen flash (front camera). */
    suspend fun shoot(ctx: Context, owner: LifecycleOwner, camera: String, flash: Boolean = false): ByteArray {
        val provider = suspendCancellableCoroutine<ProcessCameraProvider> { c ->
            val f = ProcessCameraProvider.getInstance(ctx)
            f.addListener({ try { c.resume(f.get()) } catch (e: Exception) { c.resumeWithException(e) } }, ContextCompat.getMainExecutor(ctx))
        }
        val ic = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setJpegQuality(92).build()
        val sel = if (camera == "front") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        if (!provider.hasCamera(sel)) throw RuntimeException("$camera camera not available")
        var screen = false
        try {
            provider.unbindAll()
            val cam = provider.bindToLifecycle(owner, sel, ic)
            if (flash) {
                if (cam.cameraInfo.hasFlashUnit()) cam.cameraControl.enableTorch(true)
                else { ScreenFlash.on(ctx); screen = true }
                delay(700)
            }
            delay(1500) // let auto-exposure / focus settle
            val raw = suspendCancellableCoroutine<ByteArray> { c ->
                val out = ByteArrayOutputStream()
                ic.takePicture(ImageCapture.OutputFileOptions.Builder(out).build(), ContextCompat.getMainExecutor(ctx),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(r: ImageCapture.OutputFileResults) { c.resume(out.toByteArray()) }
                        override fun onError(e: ImageCaptureException) { c.resumeWithException(e) }
                    })
            }
            return normalize(raw)
        } finally {
            if (screen) ScreenFlash.off(ctx)
            runCatching { provider.unbindAll() }
        }
    }

    /** Average brightness 0..255 of a jpeg (cheap 1/16 sample). */
    fun luma(jpeg: ByteArray): Int {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = 16 }) ?: return 0
        val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        if (px.isEmpty()) return 0
        var s = 0L; for (p in px) s += (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
        return (s / px.size).toInt()
    }

    /** Small copy for the AI (≈1 image tile = fewest tokens). */
    fun shrink(jpeg: ByteArray, maxSide: Int = 768): ByteArray {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val sc = maxSide.toFloat() / max(bmp.width, bmp.height); if (sc >= 1f) return jpeg
        val b = Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true)
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 78, it) }.toByteArray()
    }

    private fun normalize(raw: ByteArray): ByteArray {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, o)
        var s = 1
        while (max(o.outWidth, o.outHeight) / s > 2400) s *= 2
        var bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = s })
            ?: throw RuntimeException("could not decode photo")
        val rot = when (ExifInterface(ByteArrayInputStream(raw)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
        }
        if (rot != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
        val sc = 1280f / max(bmp.width, bmp.height)
        if (sc < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true)
        val bo = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, bo)
        return bo.toByteArray()
    }
}
