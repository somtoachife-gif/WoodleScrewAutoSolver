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
        private const val MIN_AFTER_TAP_MS = 90L
        private const val FORCE_REPLAN_MS = 520L
        private const val VERIFY_START_MS = 140L
        private const val VERIFY_TIMEOUT_MS = 700L
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
    private var failedTapX = -9999
    private var failedTapY = -9999
    private var failedTapAt = 0L

    private var levelStable = 0
    private var lastLevelX = -1
    private var lastLevelY = -1

    private var previousBoardSignature: IntArray? = null
    private var stableBoardFrames = 0
    private var preTapSignature: IntArray? = null
    private var awaitingTapResult = false

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
                val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                else { @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA) }
                if (code == Activity.RESULT_OK && data != null) {
                    startForeground(NOTIFICATION_ID, buildNotification("V6: waiting for Woodle Screw"))
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
                val padded = Bitmap.createBitmap(image.width + rowPadding/pixelStride, image.height, Bitmap.Config.ARGB_8888)
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

        if (!AutoTapAccessibilityService.isWoodleForeground()) {
            levelStable = 0
            resetStability()
            awaitingTapResult = false
            preTapSignature = null
            updateNotification("V6 paused: Woodle Screw not foreground")
            return
        }

        val detection = PuzzleDetector.detect(frame)

        when (detection.state) {
            PuzzleDetector.ScreenState.WAIT -> {
                levelStable = 0
                resetStability()
                awaitingTapResult = false
                preTapSignature = null
                updateNotification("V6: waiting for board / reward / ad")
            }

            PuzzleDetector.ScreenState.LEVEL_BUTTON -> {
                resetStability()
                awaitingTapResult = false
                preTapSignature = null
                val x = detection.levelButtonX ?: return
                val y = detection.levelButtonY ?: return
                val same = abs(x-lastLevelX) < 28 && abs(y-lastLevelY) < 28
                if (same) levelStable++ else { levelStable=1; lastLevelX=x; lastLevelY=y }
                updateNotification("V6: LEVEL button ready")
                if (levelStable < 2 || now-lastTapAt < 320L) return
                if (AutoTapAccessibilityService.tap(x.toFloat(),y.toFloat())) {
                    lastTapX=x; lastTapY=y; lastTapAt=now; levelStable=0
                    updateNotification("V6: starting next level")
                }
            }

            PuzzleDetector.ScreenState.PUZZLE -> {
                levelStable = 0

                if (awaitingTapResult) {
                    when (verifyTapOutcome(frame, now)) {
                        0 -> { updateNotification("V6: verifying move…"); return }
                        1 -> { awaitingTapResult=false; preTapSignature=null; resetStability() }
                        -1 -> {
                            awaitingTapResult=false
                            preTapSignature=null
                            failedTapX=lastTapX; failedTapY=lastTapY; failedTapAt=now
                            resetStability()
                            updateNotification("V6 recovery: move had no effect — replanning")
                            return
                        }
                    }
                }

                if (!boardReadyForNextMove(frame, now)) {
                    updateNotification("V6: board moving — watching")
                    return
                }

                val plan = BoardPlanner.plan(detection)
                if (plan == null) {
                    updateNotification("V6: no confident candidates — rescanning")
                    return
                }

                if (!plan.safeToTap) {
                    val pct = (plan.confidence*100f).toInt()
                    updateNotification("V6 unsure ($pct%, margin ${plan.margin.toInt()}) — waiting")
                    return
                }

                val candidate = plan.screw
                val minY=(frame.height*.25f).toInt(); val maxY=(frame.height*.80f).toInt()
                val minX=(frame.width*.01f).toInt(); val maxX=(frame.width*.99f).toInt()
                if (candidate.x !in minX..maxX || candidate.y !in minY..maxY) {
                    updateNotification("V6 rejected unsafe move")
                    return
                }

                val sameAsLast = abs(candidate.x-lastTapX)<22 && abs(candidate.y-lastTapY)<22
                if (sameAsLast && now-lastTapAt<560L) return

                val sameAsFailed = abs(candidate.x-failedTapX)<28 && abs(candidate.y-failedTapY)<28
                if (sameAsFailed && now-failedTapAt<1800L) {
                    updateNotification("V6 recovery: avoiding failed coordinate")
                    return
                }

                val pct=(plan.confidence*100f).toInt()
                updateNotification("V6 AI: $pct% confident, depth ${plan.depth}, matches=${plan.visibleMatches}")

                preTapSignature = boardSignature(frame)
                if (AutoTapAccessibilityService.tap(candidate.x.toFloat(),candidate.y.toFloat())) {
                    lastTapX=candidate.x; lastTapY=candidate.y; lastTapAt=now
                    awaitingTapResult=true
                    resetStability()
                    updateNotification("V6: move made — checking result")
                } else {
                    preTapSignature=null
                }
            }
        }
    }

    // 0 = still waiting, 1 = board changed, -1 = tap appears to have failed.
    private fun verifyTapOutcome(frame: Bitmap, now: Long): Int {
        val elapsed = now-lastTapAt
        if (elapsed < VERIFY_START_MS) return 0
        val before = preTapSignature ?: return 1
        val after = boardSignature(frame)
        val diff = signatureDiff(before, after)
        if (diff >= 5.0f) return 1
        if (elapsed >= VERIFY_TIMEOUT_MS) return -1
        return 0
    }

    private fun boardReadyForNextMove(frame: Bitmap, now: Long): Boolean {
        if (lastTapAt == 0L || now-lastTapAt > 1000L) return true
        val elapsed = now-lastTapAt
        if (elapsed < MIN_AFTER_TAP_MS) return false
        val sig=boardSignature(frame)
        val prev=previousBoardSignature
        previousBoardSignature=sig
        if (prev==null) { stableBoardFrames=0; return elapsed>=FORCE_REPLAN_MS }
        val avgDiff=signatureDiff(sig,prev)
        if (avgDiff<7.5f) stableBoardFrames++ else stableBoardFrames=0
        return stableBoardFrames>=1 || elapsed>=FORCE_REPLAN_MS
    }

    private fun signatureDiff(a:IntArray,b:IntArray):Float {
        val n=minOf(a.size,b.size)
        if(n==0) return 999f
        var total=0
        for(i in 0 until n) total+=abs(a[i]-b[i])
        return total.toFloat()/n
    }

    private fun boardSignature(frame: Bitmap): IntArray {
        val cols=8; val rows=10; val out=IntArray(cols*rows); var k=0
        for(ry in 0 until rows){
            val yf=.25f+(.55f*(ry+.5f)/rows)
            val y=(frame.height*yf).toInt().coerceIn(0,frame.height-1)
            for(cx in 0 until cols){
                val xf=.02f+(.96f*(cx+.5f)/cols)
                val x=(frame.width*xf).toInt().coerceIn(0,frame.width-1)
                val c=frame.getPixel(x,y)
                val r=(c shr 16) and 255; val g=(c shr 8) and 255; val b=c and 255
                out[k++]=(r*3+g*6+b)/10
            }
        }
        return out
    }

    private fun resetStability(){ previousBoardSignature=null; stableBoardFrames=0 }

    private fun stopSolver(){
        running=false
        reader?.setOnImageAvailableListener(null,null)
        display?.release(); display=null
        reader?.close(); reader=null
        projection?.stop(); projection=null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel(){
        if(Build.VERSION.SDK_INT>=26){
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID,"Woodle Solver",NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text:String):Notification = NotificationCompat.Builder(this,CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .setContentTitle("Woodle Solver V6 AI")
        .setContentText(text)
        .setOngoing(true)
        .build()

    private fun updateNotification(text:String){
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,buildNotification(text))
    }

    override fun onBind(intent:Intent?)=null
    override fun onDestroy(){ running=false; handlerThread.quitSafely(); super.onDestroy() }
}
