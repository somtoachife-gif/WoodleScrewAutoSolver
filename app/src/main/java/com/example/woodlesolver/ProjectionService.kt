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

    @Volatile private var running = false

    private var lastTapX = -9999
    private var lastTapY = -9999
    private var lastTapAt = 0L
    private var levelStable = 0
    private var lastLevelX = -1
    private var lastLevelY = -1

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
                    startForeground(NOTIFICATION_ID, buildNotification("V4 Planner: waiting for Woodle Screw"))
                    startCapture(code, data)
                } else stopSelf()
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
            override fun onStop() { handler.post { stopSolver() } }
        }, handler)

        display = projection?.createVirtualDisplay(
            "WoodleCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface, null, handler
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
            } finally { image.close() }
        }, handler)
    }

    private fun analyze(frame: Bitmap) {
        val now = SystemClock.elapsedRealtime()

        // Never tap outside Woodle Screw. If an interstitial ad opens another app,
        // the solver freezes until Woodle is foreground again.
        if (!AutoTapAccessibilityService.isWoodleForeground()) {
            levelStable = 0
            updateNotification("V4 paused: Woodle Screw not foreground")
            return
        }

        val detection = PuzzleDetector.detect(frame)

        when (detection.state) {
            PuzzleDetector.ScreenState.WAIT -> {
                levelStable = 0
                updateNotification("V4: waiting for board / reward / ad")
            }

            PuzzleDetector.ScreenState.LEVEL_BUTTON -> {
                val x = detection.levelButtonX ?: return
                val y = detection.levelButtonY ?: return
                val same = kotlin.math.abs(x-lastLevelX) < 28 && kotlin.math.abs(y-lastLevelY) < 28
                if (same) levelStable++ else {
                    levelStable = 1
                    lastLevelX = x
                    lastLevelY = y
                }

                updateNotification("V4: LEVEL button ready")
                if (levelStable < 2) return
                if (now - lastTapAt < 500) return

                if (AutoTapAccessibilityService.tap(x.toFloat(), y.toFloat())) {
                    lastTapX = x
                    lastTapY = y
                    lastTapAt = now
                    levelStable = 0
                    updateNotification("V4: starting next level")
                }
            }

            PuzzleDetector.ScreenState.PUZZLE -> {
                levelStable = 0

                // Re-read and re-plan quickly after each move. 220 ms is long enough
                // for the screw-removal animation to start but much faster than V2.
                if (now - lastTapAt < 220) return

                val plan = PuzzleDetector.planMove(detection)
                if (plan == null) {
                    updateNotification(
                        "V4 planning: targets=${detection.targets.size}, screws=${detection.screws.size}, waiting"
                    )
                    return
                }

                val candidate = plan.screw

                // Hard board-only zone: never tap collectors, tools, navigation or ads.
                val minY = (frame.height * .25f).toInt()
                val maxY = (frame.height * .80f).toInt()
                val minX = (frame.width * .01f).toInt()
                val maxX = (frame.width * .99f).toInt()
                if (candidate.x !in minX..maxX || candidate.y !in minY..maxY) {
                    updateNotification("V4 rejected unsafe tap")
                    return
                }

                // Do not hammer a coordinate while the old screw is disappearing.
                val sameAsLast =
                    kotlin.math.abs(candidate.x-lastTapX) < 22 &&
                    kotlin.math.abs(candidate.y-lastTapY) < 22
                if (sameAsLast && now-lastTapAt < 700) return

                updateNotification(
                    "V4 plan: target ${plan.targetIndex+1}, visible matches=${plan.matchingVisible}"
                )

                if (AutoTapAccessibilityService.tap(candidate.x.toFloat(), candidate.y.toFloat())) {
                    lastTapX = candidate.x
                    lastTapY = candidate.y
                    lastTapAt = now
                    updateNotification("V4: move made — replanning")
                }
            }
        }
    }

    private fun stopSolver() {
        running = false
        reader?.setOnImageAvailableListener(null, null)
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Woodle Solver", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Woodle Solver V4 Planner")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        running = false
        handlerThread.quitSafely()
        super.onDestroy()
    }
}
