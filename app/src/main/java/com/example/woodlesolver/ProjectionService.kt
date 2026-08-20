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

/**
 * V13 diagnostic capture service.
 * IMPORTANT: this build never auto-taps. It only analyzes and paints an overlay.
 * The only screw tap possible is the explicit TAP SELECTED overlay button.
 */
class ProjectionService : Service() {
    companion object {
        const val ACTION_START="woodle.START"
        const val ACTION_STOP="woodle.STOP"
        const val EXTRA_RESULT_CODE="resultCode"
        const val EXTRA_RESULT_DATA="resultData"
        private const val CHANNEL_ID="solver"
        private const val NOTIFICATION_ID=71
        private const val ANALYZE_INTERVAL_MS=140L
    }

    private var projection:MediaProjection?=null
    private var display:VirtualDisplay?=null
    private var reader:ImageReader?=null
    private val handlerThread=HandlerThread("WoodleSolverCapture")
    private lateinit var handler:Handler
    @Volatile private var running=false
    private var lastAnalyzeAt=0L

    override fun onCreate(){
        super.onCreate()
        handlerThread.start()
        handler=Handler(handlerThread.looper)
        createNotificationChannel()
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        when(intent?.action){
            ACTION_STOP->stopSolver()
            ACTION_START->{
                val code=intent.getIntExtra(EXTRA_RESULT_CODE,Activity.RESULT_CANCELED)
                val data=if(Build.VERSION.SDK_INT>=33) intent.getParcelableExtra(EXTRA_RESULT_DATA,Intent::class.java)
                else {@Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)}
                if(code==Activity.RESULT_OK&&data!=null){
                    startForeground(NOTIFICATION_ID,buildNotification("V13 debug: starting vision overlay"))
                    startCapture(code,data)
                }else stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode:Int,resultData:Intent){
        if(running)return
        running=true
        val dm=resources.displayMetrics
        val width=dm.widthPixels
        val height=dm.heightPixels
        val density=dm.densityDpi
        reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2)
        val mgr=getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection=mgr.getMediaProjection(resultCode,resultData)
        projection?.registerCallback(object:MediaProjection.Callback(){
            override fun onStop(){ handler.post{stopSolver()} }
        },handler)
        display=projection?.createVirtualDisplay(
            "WoodleCapture",width,height,density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface,null,handler
        )
        reader?.setOnImageAvailableListener({r->
            val image=r.acquireLatestImage()?:return@setOnImageAvailableListener
            try{
                if(!running)return@setOnImageAvailableListener
                val now=SystemClock.elapsedRealtime()
                if(now-lastAnalyzeAt<ANALYZE_INTERVAL_MS)return@setOnImageAvailableListener
                lastAnalyzeAt=now

                val p=image.planes[0]
                val rowPadding=p.rowStride-p.pixelStride*image.width
                val padded=Bitmap.createBitmap(
                    image.width+rowPadding/p.pixelStride,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )
                padded.copyPixelsFromBuffer(p.buffer)
                val frame=Bitmap.createBitmap(padded,0,0,image.width,image.height)
                padded.recycle()
                analyze(frame)
                frame.recycle()
            }finally{image.close()}
        },handler)
    }

    private fun analyze(frame:Bitmap){
        if(!AutoTapAccessibilityService.isWoodleForeground()){
            AutoTapAccessibilityService.updateDebugOverlay(
                DebugOverlayView.Snapshot(
                    frame.width,frame.height,"PAUSED",emptyList(),emptyList(),0,0,
                    "selected: none","Open Woodle Screw. V13 will not auto-tap."
                ),null
            )
            updateNotification("V13 paused: Woodle Screw not foreground")
            return
        }

        val raw=PuzzleDetector.detect(frame)
        when(raw.state){
            PuzzleDetector.ScreenState.WAIT->{
                AutoTapAccessibilityService.updateDebugOverlay(
                    DebugOverlayView.Snapshot(
                        frame.width,frame.height,"WAIT",emptyList(),emptyList(),0,0,
                        "selected: none","No puzzle detected. Ads/rewards are intentionally ignored."
                    ),null
                )
                updateNotification("V13 debug: waiting for puzzle")
            }

            PuzzleDetector.ScreenState.LEVEL_BUTTON->{
                val x=raw.levelButtonX ?: -1
                val y=raw.levelButtonY ?: -1
                AutoTapAccessibilityService.updateDebugOverlay(
                    DebugOverlayView.Snapshot(
                        frame.width,frame.height,"LEVEL",emptyList(),emptyList(),0,0,
                        "LEVEL detected at ($x,$y)","Tap the LEVEL button yourself. V13 auto-tapping is OFF."
                    ),null
                )
                updateNotification("V13 debug: LEVEL detected — manual tap only")
            }

            PuzzleDetector.ScreenState.PUZZLE->{
                // Use normal V11 filtering for the debug view. Rejected raw candidates
                // are also drawn in red so we can see if the filter is throwing away real screws.
                val vision=VisionReliability.refine(frame,raw,careful=false)
                val d=vision.detection
                val structure=PieceAnalyzer.analyze(frame,d)
                val graph=BoardGraphAI.build(frame,d,structure)
                val plan=BoardPlanner.plan(this,d,structure,graph)
                val selected=plan?.screw

                val trustedMarkers=d.screws.map { s ->
                    val cls=VisionReliability.canonical(s.hsv).name.lowercase()
                    val piece=structure.byScrew[s]?.pieceId ?: -1
                    DebugOverlayView.Marker(
                        s.x.toFloat(),s.y.toFloat(),
                        "$cls ${(s.score*100f).toInt()}%",
                        selected != null && kotlin.math.abs(selected.x-s.x)<12 && kotlin.math.abs(selected.y-s.y)<12,
                        piece,
                        rejected=false
                    )
                }
                val rejectedMarkers=vision.rejectedScrews.map { s ->
                    DebugOverlayView.Marker(
                        s.x.toFloat(),s.y.toFloat(),
                        VisionReliability.canonical(s.hsv).name.lowercase(),
                        false,-1,true
                    )
                }

                val targets=vision.targetClasses.map{it.name.lowercase()}
                val selectedText=if(plan==null){
                    "selected: NONE — planner found no candidate"
                }else{
                    val cls=VisionReliability.canonical(plan.screw.hsv).name.lowercase()
                    "SELECTED $cls @(${plan.screw.x},${plan.screw.y}) conf ${(plan.confidence*100f).toInt()}% risk ${(plan.deadlockRisk*100f).toInt()}%"
                }
                val status=if(plan==null){
                    "trusted ${d.screws.size}/${raw.screws.size}; no manual tap enabled"
                }else{
                    "${plan.riskReason}; depth ${plan.depth}; piece P${plan.pieceId+1}; score ${plan.searchScore.toInt()}"
                }

                AutoTapAccessibilityService.updateDebugOverlay(
                    DebugOverlayView.Snapshot(
                        frame.width,frame.height,"PUZZLE",
                        trustedMarkers+rejectedMarkers,
                        targets,vision.rejected,graph.pieces.size,
                        selectedText,status
                    ),selected
                )
                updateNotification("V13 debug: ${d.screws.size} trusted, ${vision.rejected} rejected")
            }
        }
    }

    private fun stopSolver(){
        running=false
        AutoTapAccessibilityService.updateDebugOverlay(null,null)
        reader?.setOnImageAvailableListener(null,null)
        display?.release();display=null
        reader?.close();reader=null
        projection?.stop();projection=null
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

    private fun buildNotification(text:String):Notification=
        NotificationCompat.Builder(this,CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Woodle Solver V13 Debug Vision")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun updateNotification(text:String){
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,buildNotification(text))
    }

    override fun onBind(intent:Intent?)=null
    override fun onDestroy(){running=false;handlerThread.quitSafely();super.onDestroy()}
}
