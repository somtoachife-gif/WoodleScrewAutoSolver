package com.example.woodlesolver

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.core.app.NotificationCompat

class ProjectionService : Service() {

    companion object {
        const val ACTION_START = "woodle.START"
        const val ACTION_STOP = "woodle.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        private const val CHANNEL_ID = "solver"
        private const val NOTIFICATION_ID = 71
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null

    private val handlerThread = HandlerThread("WoodleSolverCapture")
    private lateinit var handler: Handler

    @Volatile
    private var running = false

    private var lastTapX = -9999
    private var lastTapY = -9999
    private var lastTapAt = 0L

    private var stableFrames = 0
    private var lastCandidateX = -1
    private var lastCandidateY = -1

    private var levelButtonStableFrames = 0
    private var lastLevelButtonX = -1
    private var lastLevelButtonY = -1

    override fun onCreate() {
        super.onCreate()
        handlerThread.start()
        handler = Handler(handlerThread.looper)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSolver()
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (code == Activity.RESULT_OK && data != null) {
                    startForeground(NOTIFICATION_ID, buildNotification("Waiting for Woodle Screw…"))
                    startCapture(code, data)
                } else {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        if (running) return
        running = true

        val dm = resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        val density = dm.densityDpi

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(resultCode, resultData)

        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { stopSolver() }
            }
        }, handler)

        display = projection?.createVirtualDisplay(
            "WoodleCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface,
            null,
            handler
        )

        reader?.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (!running) return@setOnImageAvailableListener

                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * image.width

                val padded = Bitmap.createBitmap(
                    image.width + rowPadding / pixelStride,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )
                padded.copyPixelsFromBuffer(buffer)

                val frame = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                padded.recycle()

                analyze(frame)
                frame.recycle()
            } finally {
                image.close()
            }
        }, handler)
    }

    private fun analyze(frame: Bitmap) {
        val now = SystemClock.elapsedRealtime()

        // Absolute safety gate: if an ad/browser/other game becomes foreground,
        // do nothing until Woodle Screw is foreground again.
        if (!AutoTapAccessibilityService.isWoodleForeground()) {
            resetStableState()
            updateNotification("Paused — Woodle Screw is not foreground")
            return
        }

        val detection = PuzzleDetector.detect(frame)

        when (detection.state) {
            PuzzleDetector.ScreenState.WAIT -> {
                // This includes loading, PERFECT/reward animation, and ads.
                // We intentionally DO NOT tap any Claim/Install/Yummy Town/ad UI.
                resetStableState()
                updateNotification("Waiting for level/reward/ad to finish")
            }

            PuzzleDetector.ScreenState.LEVEL_BUTTON -> {
                stableFrames = 0
                val x = detection.levelButtonX ?: return
                val y = detection.levelButtonY ?: return

                val sameButton =
                    kotlin.math.abs(x - lastLevelButtonX) < 30 &&
                    kotlin.math.abs(y - lastLevelButtonY) < 30

                if (sameButton) {
                    levelButtonStableFrames++
                } else {
                    levelButtonStableFrames = 1
                    lastLevelButtonX = x
                    lastLevelButtonY = y
                }

                updateNotification("Level button found (${levelButtonStableFrames}/3)")

                // Require several consecutive frames so a green ad graphic cannot
                // trigger a one-frame false positive.
                if (levelButtonStableFrames < 3) return
                if (now - lastTapAt < 1800) return

                if (AutoTapAccessibilityService.tap(x.toFloat(), y.toFloat())) {
                    lastTapX = x
                    lastTapY = y
                    lastTapAt = now
                    levelButtonStableFrames = 0
                    updateNotification("Starting next level")
                }
            }

            PuzzleDetector.ScreenState.PUZZLE -> {
                levelButtonStableFrames = 0

                // Let the board settle after each tap / level transition.
                if (now - lastTapAt < 700) return

                val candidate = PuzzleDetector.chooseTap(detection)
                updateNotification(
                    "Puzzle: targets=${detection.targets.size}, screws=${detection.screws.size}"
                )

                if (candidate == null) {
                    stableFrames = 0
                    return
                }

                // Extra hard safety zone copied from the actual gameplay layout:
                // never touch collector boxes, tools, bottom banners, or navigation.
                val minY = (frame.height * .22f).toInt()
                val maxY = (frame.height * .82f).toInt()
                val minX = (frame.width * .02f).toInt()
                val maxX = (frame.width * .98f).toInt()
                if (candidate.x !in minX..maxX || candidate.y !in minY..maxY) {
                    stableFrames = 0
                    return
                }

                val closeToLastCandidate =
                    kotlin.math.abs(candidate.x - lastCandidateX) < 22 &&
                    kotlin.math.abs(candidate.y - lastCandidateY) < 22

                if (closeToLastCandidate) {
                    stableFrames++
                } else {
                    stableFrames = 1
                    lastCandidateX = candidate.x
                    lastCandidateY = candidate.y
                }

                if (stableFrames < 2) return

                val sameAsLastTap =
                    kotlin.math.abs(candidate.x - lastTapX) < 24 &&
                    kotlin.math.abs(candidate.y - lastTapY) < 24
                if (sameAsLastTap && now - lastTapAt < 2400) return

                if (AutoTapAccessibilityService.tap(candidate.x.toFloat(), candidate.y.toFloat())) {
                    lastTapX = candidate.x
                    lastTapY = candidate.y
                    lastTapAt = now
                    stableFrames = 0
                    updateNotification("Tapped screw (${candidate.x}, ${candidate.y})")
                }
            }
        }
    }

    private fun resetStableState() {
        stableFrames = 0
        levelButtonStableFrames = 0
        lastCandidateX = -1
        lastCandidateY = -1
        lastLevelButtonX = -1
        lastLevelButtonY = -1
    }

    private fun stopSolver() {
        running = false
        reader?.setOnImageAvailableListener(null, null)
        display?.release()
        display = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Woodle Solver", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Woodle Solver running")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        running = false
        handlerThread.quitSafely()
        super.onDestroy()
    }
}
