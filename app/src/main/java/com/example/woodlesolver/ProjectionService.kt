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

/**
 * V14 ground-up solver.
 *
 * This intentionally bypasses the old PuzzleDetector / VisionReliability / planner
 * state machine. The old system could sit in WAIT forever if either target sampling
 * or screw detection failed. V14 reads the actual Woodle collector frames and screw
 * heads directly and makes one verified move at a time.
 */
class ProjectionService : Service() {
    companion object {
        const val ACTION_START="woodle.START"
        const val ACTION_STOP="woodle.STOP"
        const val EXTRA_RESULT_CODE="resultCode"
        const val EXTRA_RESULT_DATA="resultData"
        private const val CHANNEL_ID="solver"
        private const val NOTIFICATION_ID=71
        private const val VERIFY_START_MS=150L
        private const val VERIFY_TIMEOUT_MS=900L
        private const val MIN_MOVE_GAP_MS=115L
    }

    private var projection:MediaProjection?=null
    private var display:VirtualDisplay?=null
    private var reader:ImageReader?=null
    private val thread=HandlerThread("WoodleV14Capture")
    private lateinit var handler:Handler
    @Volatile private var running=false

    private var lastTapAt=0L
    private var lastTapX=-9999
    private var lastTapY=-9999
    private var levelX=-9999
    private var levelY=-9999
    private var levelStable=0

    private var awaitingResult=false
    private var expectedX=-9999
    private var expectedY=-9999
    private var expectedColor=GameplayVision.Color.UNKNOWN
    private var tapBeforeScrews=0

    // Two-frame candidate persistence: prevents one noisy frame from causing a tap.
    private var candidateX=-9999
    private var candidateY=-9999
    private var candidateColor=GameplayVision.Color.UNKNOWN
    private var candidateFrames=0

    override fun onCreate(){
        super.onCreate();thread.start();handler=Handler(thread.looper);createNotificationChannel()
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        when(intent?.action){
            ACTION_STOP->stopSolver()
            ACTION_START->{
                val code=intent.getIntExtra(EXTRA_RESULT_CODE,Activity.RESULT_CANCELED)
                val data=if(Build.VERSION.SDK_INT>=33) intent.getParcelableExtra(EXTRA_RESULT_DATA,Intent::class.java)
                else {@Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)}
                if(code==Activity.RESULT_OK&&data!=null){
                    startForeground(NOTIFICATION_ID,notification("V14: waiting for Woodle Screw"))
                    startCapture(code,data)
                } else stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(code:Int,data:Intent){
        if(running)return
        running=true
        val dm=resources.displayMetrics
        val w=dm.widthPixels;val h=dm.heightPixels
        reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2)
        val mgr=getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection=mgr.getMediaProjection(code,data)
        projection?.registerCallback(object:MediaProjection.Callback(){override fun onStop(){handler.post{stopSolver()}}},handler)
        display=projection?.createVirtualDisplay("WoodleV14",w,h,dm.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader?.surface,null,handler)
        reader?.setOnImageAvailableListener({r->
            val image=r.acquireLatestImage()?:return@setOnImageAvailableListener
            try{
                if(!running)return@setOnImageAvailableListener
                val p=image.planes[0]
                val pad=p.rowStride-p.pixelStride*image.width
                val padded=Bitmap.createBitmap(image.width+pad/p.pixelStride,image.height,Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(p.buffer)
                val frame=Bitmap.createBitmap(padded,0,0,image.width,image.height)
                padded.recycle()
                analyze(frame)
                frame.recycle()
            }finally{image.close()}
        },handler)
    }

    private fun analyze(frame:Bitmap){
        val now=SystemClock.elapsedRealtime()
        if(!AutoTapAccessibilityService.isWoodleForeground()){
            resetCandidate();levelStable=0;awaitingResult=false
            update("V14 paused: Woodle not foreground")
            return
        }

        val d=GameplayVision.analyze(frame)
        when(d.screen){
            GameplayVision.Screen.HOME -> handleHome(d,now)
            GameplayVision.Screen.PUZZLE -> handlePuzzle(d,now)
            GameplayVision.Screen.WAIT -> {
                resetCandidate();levelStable=0
                update("V14: waiting through loading / reward / ad")
            }
        }
    }

    private fun handleHome(d:GameplayVision.Detection,now:Long){
        awaitingResult=false;resetCandidate()
        val x=d.levelX?:return;val y=d.levelY?:return
        val same=abs(x-levelX)<35&&abs(y-levelY)<35
        if(same)levelStable++ else {levelX=x;levelY=y;levelStable=1}
        update("V14: LEVEL found at $x,$y (${levelStable}/2)")
        if(levelStable<2||now-lastTapAt<500L)return

        // IMPORTANT: MediaProjection and Accessibility both use display coordinates
        // here, so use the raw coordinate. Do not remap through root-window bounds.
        if(AutoTapAccessibilityService.tap(x.toFloat(),y.toFloat())){
            lastTapAt=now;lastTapX=x;lastTapY=y;levelStable=0
            update("V14: tapped LEVEL — waiting for puzzle")
        }
    }

    private fun handlePuzzle(d:GameplayVision.Detection,now:Long){
        levelStable=0
        val active=d.trays.filter{it.active&&it.color!=GameplayVision.Color.UNKNOWN}
        val trayText=active.joinToString("/"){"${it.color.name.lowercase()}:${it.filled}"}.ifBlank{"none"}

        if(awaitingResult){
            val elapsed=now-lastTapAt
            if(elapsed<VERIFY_START_MS){update("V14 verifying ${expectedColor.name.lowercase()}…");return}
            val stillThere=d.screws.any{abs(it.x-expectedX)<34&&abs(it.y-expectedY)<34&&it.color==expectedColor}
            val screwCountDropped=d.screws.size<tapBeforeScrews
            if(!stillThere||screwCountDropped){
                awaitingResult=false;resetCandidate()
                update("V14: move confirmed | trays $trayText | ${d.screws.size} screws")
                return
            }
            if(elapsed<VERIFY_TIMEOUT_MS){update("V14: waiting for screw to clear…");return}
            awaitingResult=false;resetCandidate()
            update("V14: tap had no effect — rescanning")
            return
        }

        if(now-lastTapAt<MIN_MOVE_GAP_MS)return
        if(active.isEmpty()){
            resetCandidate();update("V14: puzzle seen, but collector color not readable yet")
            return
        }
        if(d.screws.isEmpty()){
            resetCandidate();update("V14: puzzle seen | trays $trayText | scanning screws…")
            return
        }

        val choice=chooseMove(d,active)
        if(choice==null){
            resetCandidate()
            val colors=d.screws.groupingBy{it.color}.eachCount().entries.joinToString(" "){"${it.key.name.lowercase()}:${it.value}"}
            update("V14: no visible screw for tray $trayText | saw $colors")
            return
        }

        val same=choice.color==candidateColor&&abs(choice.x-candidateX)<26&&abs(choice.y-candidateY)<26
        if(same)candidateFrames++ else {
            candidateX=choice.x;candidateY=choice.y;candidateColor=choice.color;candidateFrames=1
        }
        if(candidateFrames<2){
            update("V14: confirming ${choice.color.name.lowercase()} screw at ${choice.x},${choice.y}")
            return
        }

        if(abs(choice.x-lastTapX)<25&&abs(choice.y-lastTapY)<25&&now-lastTapAt<1200L){
            resetCandidate();return
        }

        update("V14: tapping ${choice.color.name.lowercase()} | trays $trayText | score ${(choice.score*100).toInt()}")
        if(AutoTapAccessibilityService.tap(choice.x.toFloat(),choice.y.toFloat())){
            lastTapAt=now;lastTapX=choice.x;lastTapY=choice.y
            expectedX=choice.x;expectedY=choice.y;expectedColor=choice.color
            tapBeforeScrews=d.screws.size;awaitingResult=true;resetCandidate()
        }
    }

    /**
     * Woodle wants screws matching the active collector colors.
     * Priority:
     *  1. Continue a tray that already has collected screws.
     *  2. Prefer a color with >=3 visible screws.
     *  3. Prefer a confident exposed screw near the top of the pile; after every
     *     move the board is rescanned so falling/rotating pieces are handled closed-loop.
     */
    private fun chooseMove(d:GameplayVision.Detection,active:List<GameplayVision.Tray>):GameplayVision.Screw?{
        var best:GameplayVision.Screw?=null
        var bestScore=Float.NEGATIVE_INFINITY
        val visibleCounts=d.screws.groupingBy{it.color}.eachCount()
        for(s in d.screws){
            val tray=active.firstOrNull{it.color==s.color}?:continue
            val count=visibleCounts[s.color]?:0
            var score=s.score*120f
            score+=tray.filled*48f
            score+=when{count>=3->85f;count==2->32f;else->0f}
            // Slight preference for higher screws because they are more often on top
            // and less likely to be hidden under another plank.
            score+=(1f-s.y/1700f).coerceIn(0f,1f)*12f
            if(score>bestScore){bestScore=score;best=s}
        }
        return best
    }

    private fun resetCandidate(){candidateX=-9999;candidateY=-9999;candidateColor=GameplayVision.Color.UNKNOWN;candidateFrames=0}

    private fun stopSolver(){
        running=false;reader?.setOnImageAvailableListener(null,null);display?.release();display=null
        reader?.close();reader=null;projection?.stop();projection=null
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    private fun createNotificationChannel(){if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID,"Woodle Solver",NotificationManager.IMPORTANCE_LOW))}
    private fun notification(t:String):Notification=NotificationCompat.Builder(this,CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Woodle Solver V14 Ground-Up").setContentText(t).setOngoing(true).build()
    private fun update(t:String){getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,notification(t))}
    override fun onBind(intent:Intent?)=null
    override fun onDestroy(){running=false;thread.quitSafely();super.onDestroy()}
}
