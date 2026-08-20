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
        const val ACTION_START="woodle.START"; const val ACTION_STOP="woodle.STOP"
        const val EXTRA_RESULT_CODE="resultCode"; const val EXTRA_RESULT_DATA="resultData"
        private const val CHANNEL_ID="solver"; private const val NOTIFICATION_ID=71
        private const val MIN_AFTER_TAP_MS=80L; private const val FORCE_REPLAN_MS=450L
        private const val VERIFY_START_MS=125L; private const val VERIFY_TIMEOUT_MS=680L
    }

    private var projection:MediaProjection?=null; private var display:VirtualDisplay?=null; private var reader:ImageReader?=null
    private val handlerThread=HandlerThread("WoodleSolverCapture"); private lateinit var handler:Handler
    @Volatile private var running=false
    private var lastTapX=-9999; private var lastTapY=-9999; private var lastTapAt=0L
    private var failedTapX=-9999; private var failedTapY=-9999; private var failedTapAt=0L
    private var levelStable=0; private var lastLevelX=-1; private var lastLevelY=-1
    private var previousBoardSignature:IntArray?=null; private var stableBoardFrames=0
    private var preTapSignature:IntArray?=null; private var awaitingTapResult=false
    private var pendingLearning:LearningModel.Features?=null
    private var pendingColor=VisionReliability.ColorClass.UNKNOWN
    private var preTapScrewCount=0
    private var uncertainStreak=0

    override fun onCreate(){super.onCreate();handlerThread.start();handler=Handler(handlerThread.looper);createNotificationChannel()}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        when(intent?.action){
            ACTION_STOP->stopSolver()
            ACTION_START->{
                val code=intent.getIntExtra(EXTRA_RESULT_CODE,Activity.RESULT_CANCELED)
                val data=if(Build.VERSION.SDK_INT>=33) intent.getParcelableExtra(EXTRA_RESULT_DATA,Intent::class.java) else {@Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)}
                if(code==Activity.RESULT_OK&&data!=null){startForeground(NOTIFICATION_ID,buildNotification("V11: waiting for Woodle Screw"));startCapture(code,data)} else stopSelf()
            }
        };return START_NOT_STICKY
    }

    private fun startCapture(resultCode:Int,resultData:Intent){
        if(running)return;running=true
        val dm=resources.displayMetrics;val width=dm.widthPixels;val height=dm.heightPixels;val density=dm.densityDpi
        reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2)
        val mgr=getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection=mgr.getMediaProjection(resultCode,resultData)
        projection?.registerCallback(object:MediaProjection.Callback(){override fun onStop(){handler.post{stopSolver()}}},handler)
        display=projection?.createVirtualDisplay("WoodleCapture",width,height,density,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader?.surface,null,handler)
        reader?.setOnImageAvailableListener({r->
            val image=r.acquireLatestImage()?:return@setOnImageAvailableListener
            try{
                if(!running)return@setOnImageAvailableListener
                val p=image.planes[0];val rowPadding=p.rowStride-p.pixelStride*image.width
                val padded=Bitmap.createBitmap(image.width+rowPadding/p.pixelStride,image.height,Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(p.buffer);val frame=Bitmap.createBitmap(padded,0,0,image.width,image.height);padded.recycle()
                analyze(frame);frame.recycle()
            }finally{image.close()}
        },handler)
    }

    private fun analyze(frame:Bitmap){
        val now=SystemClock.elapsedRealtime()
        if(!AutoTapAccessibilityService.isWoodleForeground()){
            levelStable=0;resetStability();awaitingTapResult=false;preTapSignature=null;pendingLearning=null;pendingColor=VisionReliability.ColorClass.UNKNOWN
            updateNotification("V11 paused: Woodle Screw not foreground");return
        }

        val raw=PuzzleDetector.detect(frame)
        val careful=uncertainStreak>=4
        val vision=VisionReliability.refine(frame,raw,careful)
        val detection=vision.detection

        when(detection.state){
            PuzzleDetector.ScreenState.WAIT->{
                levelStable=0;resetStability();awaitingTapResult=false;preTapSignature=null;pendingLearning=null;pendingColor=VisionReliability.ColorClass.UNKNOWN
                updateNotification("V11: waiting for board / reward / ad")
            }
            PuzzleDetector.ScreenState.LEVEL_BUTTON->{
                CollectorTracker.reset();uncertainStreak=0;resetStability();awaitingTapResult=false;preTapSignature=null;pendingLearning=null
                val x=detection.levelButtonX?:return;val y=detection.levelButtonY?:return
                val same=abs(x-lastLevelX)<28&&abs(y-lastLevelY)<28;if(same)levelStable++ else {levelStable=1;lastLevelX=x;lastLevelY=y}
                updateNotification("V11: LEVEL button ready")
                if(levelStable<2||now-lastTapAt<300L)return
                if(AutoTapAccessibilityService.tapMapped(x.toFloat(),y.toFloat(),frame.width,frame.height)){
                    lastTapX=x;lastTapY=y;lastTapAt=now;levelStable=0;updateNotification("V11: starting next level")
                }
            }
            PuzzleDetector.ScreenState.PUZZLE->{
                levelStable=0
                if(awaitingTapResult){
                    val outcome=verifyTapOutcome(frame,now)
                    if(outcome==0){updateNotification("V11: verifying move…");return}
                    if(outcome==1){
                        val before=preTapSignature;val after=boardSignature(frame);val diff=if(before==null)5f else signatureDiff(before,after)
                        val afterRaw=PuzzleDetector.detect(frame);val reveal=maxOf(0,afterRaw.screws.size-preTapScrewCount)
                        val reward=(.28f+(diff/22f).coerceIn(0f,.57f)+(reveal*.08f).coerceAtMost(.15f)).coerceIn(0f,1f)
                        pendingLearning?.let{LearningModel.observe(this,it,reward)};CollectorTracker.observeSuccessful(pendingColor)
                        awaitingTapResult=false;preTapSignature=null;pendingLearning=null;pendingColor=VisionReliability.ColorClass.UNKNOWN;uncertainStreak=0;resetStability()
                    }else{
                        pendingLearning?.let{LearningModel.observe(this,it,0f)};awaitingTapResult=false;preTapSignature=null;pendingLearning=null
                        failedTapX=lastTapX;failedTapY=lastTapY;failedTapAt=now;pendingColor=VisionReliability.ColorClass.UNKNOWN;uncertainStreak++
                        resetStability();updateNotification("V11 recovery: failed tap learned — careful scan");return
                    }
                }

                if(!boardReadyForNextMove(frame,now)){updateNotification("V11: board moving — watching");return}
                if(detection.screws.isEmpty()){
                    uncertainStreak++;updateNotification("V11 ${if(careful)"careful" else "vision"}: no trusted screws; rejected ${vision.rejected}");return
                }

                val structure=PieceAnalyzer.analyze(frame,detection)
                val plan=BoardPlanner.plan(this,detection,structure)
                if(plan==null){uncertainStreak++;updateNotification("V11: no safe plan — careful rescan ${vision.rejected} rejected");return}
                if(!plan.safeToTap){
                    uncertainStreak++;val pct=(plan.confidence*100f).toInt();updateNotification("V11 unsure $pct% | ${if(careful)"CAREFUL" else "fast"} scan");return
                }

                val c=plan.screw
                val minY=(frame.height*.25f).toInt();val maxY=(frame.height*.80f).toInt();val minX=(frame.width*.01f).toInt();val maxX=(frame.width*.99f).toInt()
                if(c.x !in minX..maxX||c.y !in minY..maxY){uncertainStreak++;updateNotification("V11 rejected unsafe coordinate");return}
                if(abs(c.x-lastTapX)<22&&abs(c.y-lastTapY)<22&&now-lastTapAt<520L)return
                if(abs(c.x-failedTapX)<28&&abs(c.y-failedTapY)<28&&now-failedTapAt<2400L){uncertainStreak++;updateNotification("V11 avoiding failed coordinate");return}

                val pct=(plan.confidence*100f).toInt();val cls=VisionReliability.canonical(c.hsv);val learned=plan.learningBonus.toInt()
                updateNotification("V11 $pct% ${cls.name.lowercase()} | depth ${plan.depth} | tray ${CollectorTracker.summary()}")
                preTapSignature=boardSignature(frame);preTapScrewCount=detection.screws.size;pendingLearning=plan.learningFeatures();pendingColor=cls
                if(AutoTapAccessibilityService.tapMapped(c.x.toFloat(),c.y.toFloat(),frame.width,frame.height)){
                    lastTapX=c.x;lastTapY=c.y;lastTapAt=now;awaitingTapResult=true;resetStability();updateNotification("V11: calibrated tap — checking result")
                }else{preTapSignature=null;pendingLearning=null;pendingColor=VisionReliability.ColorClass.UNKNOWN;uncertainStreak++}
            }
        }
    }

    private fun verifyTapOutcome(frame:Bitmap,now:Long):Int{val elapsed=now-lastTapAt;if(elapsed<VERIFY_START_MS)return 0;val before=preTapSignature?:return 1;val d=signatureDiff(before,boardSignature(frame));if(d>=5f)return 1;if(elapsed>=VERIFY_TIMEOUT_MS)return -1;return 0}
    private fun boardReadyForNextMove(frame:Bitmap,now:Long):Boolean{if(lastTapAt==0L||now-lastTapAt>950L)return true;val elapsed=now-lastTapAt;if(elapsed<MIN_AFTER_TAP_MS)return false;val sig=boardSignature(frame);val prev=previousBoardSignature;previousBoardSignature=sig;if(prev==null){stableBoardFrames=0;return elapsed>=FORCE_REPLAN_MS};val d=signatureDiff(sig,prev);if(d<7.3f)stableBoardFrames++ else stableBoardFrames=0;return stableBoardFrames>=1||elapsed>=FORCE_REPLAN_MS}
    private fun signatureDiff(a:IntArray,b:IntArray):Float{val n=minOf(a.size,b.size);if(n==0)return 999f;var total=0;for(i in 0 until n)total+=abs(a[i]-b[i]);return total.toFloat()/n}
    private fun boardSignature(frame:Bitmap):IntArray{val cols=9;val rows=11;val out=IntArray(cols*rows);var k=0;for(ry in 0 until rows){val y=(frame.height*(.25f+.55f*(ry+.5f)/rows)).toInt().coerceIn(0,frame.height-1);for(cx in 0 until cols){val x=(frame.width*(.02f+.96f*(cx+.5f)/cols)).toInt().coerceIn(0,frame.width-1);val c=frame.getPixel(x,y);val r=(c shr 16)and 255;val g=(c shr 8)and 255;val b=c and 255;out[k++]=(r*3+g*6+b)/10}};return out}
    private fun resetStability(){previousBoardSignature=null;stableBoardFrames=0}
    private fun stopSolver(){running=false;reader?.setOnImageAvailableListener(null,null);display?.release();display=null;reader?.close();reader=null;projection?.stop();projection=null;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    private fun createNotificationChannel(){if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID,"Woodle Solver",NotificationManager.IMPORTANCE_LOW))}
    private fun buildNotification(text:String):Notification=NotificationCompat.Builder(this,CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Woodle Solver V11 Vision AI").setContentText(text).setOngoing(true).build()
    private fun updateNotification(text:String){getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,buildNotification(text))}
    override fun onBind(intent:Intent?)=null
    override fun onDestroy(){running=false;handlerThread.quitSafely();super.onDestroy()}
}
