package id.projectasisten.lens.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import id.projectasisten.lens.R
import id.projectasisten.lens.core.BitmapSecurityInspector
import id.projectasisten.lens.core.ScreenshotRepository

/**
 * Strictly one-shot Foreground Service for capturing a single frame via MediaProjection
 * when the user activates Scan/Translate without Default Assistant or API 30+ Accessibility screenshot.
 *
 * Immediately stops MediaProjection, releases VirtualDisplay/ImageReader, and calls stopSelf()
 * as soon as the single frame is captured.
 */
class MediaProjectionCaptureService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var frameCaptured = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startMinimalForeground()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            notifyAndStop(false)
            return START_NOT_STICKY
        }

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val densityDpi = metrics.densityDpi

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = runCatching {
            projectionManager.getMediaProjection(resultCode, resultData)
        }.getOrNull()

        if (projection == null) {
            notifyAndStop(false)
            return START_NOT_STICKY
        }
        mediaProjection = projection

        // Required on Android 14+ (API 34+): register callback before createVirtualDisplay
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                releaseCaptureResources()
            }
        }, mainHandler)

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        frameCaptured = false

        reader.setOnImageAvailableListener({ availableReader ->
            if (frameCaptured) return@setOnImageAvailableListener
            val image = runCatching { availableReader.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            frameCaptured = true

            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val paddedWidth = width + rowPadding / pixelStride
                val rawBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                rawBitmap.copyPixelsFromBuffer(buffer)

                val croppedToScreen = if (paddedWidth != width) {
                    Bitmap.createBitmap(rawBitmap, 0, 0, width, height).also {
                        rawBitmap.recycle()
                    }
                } else {
                    rawBitmap
                }

                if (BitmapSecurityInspector.isLikelyBlankOrSecure(croppedToScreen)) {
                    croppedToScreen.recycle()
                    ScreenshotRepository.storeMemoryBitmap(null)
                    ScreenshotRepository.lastCaptureBlockedBySecureFlag = true
                    notifyAndStop(false)
                } else {
                    ScreenshotRepository.storeMemoryBitmap(croppedToScreen)
                    ScreenshotRepository.lastCaptureBlockedBySecureFlag = false
                    notifyAndStop(true)
                }
            } finally {
                image.close()
            }
        }, mainHandler)

        // Small delay (120ms) so the MediaProjection system dialog has finished fading out
        mainHandler.postDelayed({
            runCatching {
                virtualDisplay = projection.createVirtualDisplay(
                    "ProjectLensSingleFrame",
                    width,
                    height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    mainHandler
                )
            }.onFailure {
                notifyAndStop(false)
            }
        }, 120L)

        // Safety timeout: never stay alive longer than 2.5 seconds even if display produces no frame
        mainHandler.postDelayed({
            if (!frameCaptured) {
                ScreenshotRepository.lastCaptureBlockedBySecureFlag = true
                notifyAndStop(false)
            }
        }, 2500L)

        return START_NOT_STICKY
    }

    private fun startMinimalForeground() {
        val channelId = "project_lens_capture_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            channelId,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)

        val notification = Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Menangkap layar…")
            .setSmallIcon(R.drawable.ic_qs_tile)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyAndStop(success: Boolean) {
        releaseCaptureResources()
        val cb = onCaptureFinishedCallback
        onCaptureFinishedCallback = null
        mainHandler.post {
            cb?.invoke(success)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseCaptureResources() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
    }

    override fun onDestroy() {
        releaseCaptureResources()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var onCaptureFinishedCallback: ((Boolean) -> Unit)? = null
    }
}
