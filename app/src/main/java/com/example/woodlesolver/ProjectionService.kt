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
import kotlin.math.abs

class ProjectionService : Service() {

    companion object {
        const val ACTION_START = "woodle.START"
        const val ACTION_STOP = "woodle.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        private const val CHANNEL_ID = "solver"
        private const val NOTIFICATION_ID = 71

        // V5 starts checking very quickly, but does not blindly tap during motion.
        private const val MIN_AFTER_TAP_MS = 90L
        private const val FORCE_REPLAN_MS = 520L
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

    private var previousBoardSignature: IntArray? = null
    private var stableBoardFrames = 0

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
                    startForeground(NOTIFICATION_ID, buildNotification("V5 AI: waiting for Woodle Screw"))
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
            } finally {
                image.close()
            }
        }, handler)
    }

    private fun analyze(frame: Bitmap) {
        val now = SystemClock.elapsedRealtime()

        // Absolute safety gate: V5 never taps an external ad/app.
        if (!AutoTapAccessibilityService.isWoodleForeground()) {
            levelStable = 0
            resetStability()
            updateNotification("V5 paused: Woodle Screw not foreground")
            return
        }

        val detection = PuzzleDetector.detect(frame)

        when (detection.state) {
            PuzzleDetector.ScreenState.WAIT -> {
                levelStable = 0
                resetStability()
                updateNotification("V5: waiting for board / reward / ad")
            }

            PuzzleDetector.ScreenState.LEVEL_BUTTON -> {
                resetStability()
                val x = detection.levelButtonX ?: return
                val y = detection.levelButtonY ?: return

                val same = abs(x-lastLevelX) < 28 && abs(y-lastLevelY) < 28
                if (same) levelStable++ else {
                    levelStable = 1
                    lastLevelX = x
                    lastLevelY = y
                }

                updateNotification("V5: LEVEL button ready")
                if (levelStable < 2) return
                if (now-lastTapAt < 350L) return

                if (AutoTapAccessibilityService.tap(x.toFloat(), y.toFloat())) {
                    lastTapX = x
                    lastTapY = y
                    lastTapAt = now
                    levelStable = 0
                    resetStability()
                    updateNotification("V5: starting next level")
                }
            }

            PuzzleDetector.ScreenState.PUZZLE -> {
                levelStable = 0

                // Adaptive timing: begin checking only 90 ms after a move. If the
                // sampled board is stable on consecutive frames, plan immediately.
                // If animation lasts longer, wait until stable; 520 ms is the fallback.
                if (!boardReadyForNextMove(frame, now)) {
                    updateNotification("V5 AI: board moving — watching")
                    return
                }

                val plan = BoardPlanner.plan(detection)
                if (plan == null) {
                    updateNotification("V5 AI: rescanning ${detection.screws.size} screws")
                    return
                }

                val candidate = plan.screw

                // Board-only safety zone. Never touch collectors, tools, nav or ads.
                val minY = (frame.height*.25f).toInt()
                val maxY = (frame.height*.80f).toInt()
                val minX = (frame.width*.01f).toInt()
                val maxX = (frame.width*.99f).toInt()
                if (candidate.x !in minX..maxX || candidate.y !in minY..maxY) {
                    updateNotification("V5 rejected unsafe move")
                    return
                }

                // Avoid double-tapping a screw while its removal animation remains.
                val sameAsLast = abs(candidate.x-lastTapX) < 22 && abs(candidate.y-lastTapY) < 22
                if (sameAsLast && now-lastTapAt < 560L) return

                updateNotification(
                    "V5 AI: depth ${plan.depth}, target ${plan.targetIndex+1}, matches=${plan.visibleMatches}"
                )

                if (AutoTapAccessibilityService.tap(candidate.x.toFloat(), candidate.y.toFloat())) {
                    lastTapX = candidate.x
                    lastTapY = candidate.y
                    lastTapAt = now
                    resetStability()
                    updateNotification("V5 AI: move made — rescanning whole board")
                }
            }
        }
    }

    private fun boardReadyForNextMove(frame: Bitmap, now: Long): Boolean {
        if (lastTapAt == 0L) return true
        val elapsed = now-lastTapAt
        if (elapsed < MIN_AFTER_TAP_MS) return false

        val sig = boardSignature(frame)
        val prev = previousBoardSignature
        previousBoardSignature = sig

        if (prev == null) {
            stableBoardFrames = 0
            return elapsed >= FORCE_REPLAN_MS
        }

        var total = 0
        val n = minOf(sig.size, prev.size)
        for (i in 0 until n) total += abs(sig[i]-prev[i])
        val avgDiff = if (n == 0) 999f else total.toFloat()/n

        // Consecutive low-difference frames mean the puzzle has visually settled.
        if (avgDiff < 7.5f) stableBoardFrames++ else stableBoardFrames = 0

        return stableBoardFrames >= 1 || elapsed >= FORCE_REPLAN_MS
    }

    private fun boardSignature(frame: Bitmap): IntArray {
        // Sample only the actual puzzle region, so animated ads/UI do not delay V5.
        val cols = 8
        val rows = 10
        val out = IntArray(cols*rows)
        var k = 0
        for (ry in 0 until rows) {
            val yf = .25f + (.55f * (ry+.5f)/rows)
            val y = (frame.height*yf).toInt().coerceIn(0, frame.height-1)
            for (cx in 0 until cols) {
                val xf = .02f + (.96f * (cx+.5f)/cols)
                val x = (frame.width*xf).toInt().coerceIn(0, frame.width-1)
                val c = frame.getPixel(x,y)
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val b = c and 255
                out[k++] = (r*3 + g*6 + b)/10
            }
        }
        return out
    }

    private fun resetStability() {
        previousBoardSignature = null
        stableBoardFrames = 0
    }

    private fun stopSolver() {
        running = false
        reader?.setOnImageAvailableListener(null,null)
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID,"Woodle Solver",NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this,CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Woodle Solver V5 AI")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,buildNotification(text))
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        running = false
        handlerThread.quitSafely()
        super.onDestroy()
    }
}
