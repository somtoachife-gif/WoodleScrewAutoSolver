package com.example.woodlesolver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button

class AutoTapAccessibilityService : AccessibilityService() {
    companion object {
        private const val WOODLE_PACKAGE = "com.wood.bolt.wordle.screw.nuts.puzzle"
        @Volatile var instance: AutoTapAccessibilityService? = null
        @Volatile private var currentPackage: String? = null

        @Volatile private var selectedX: Float? = null
        @Volatile private var selectedY: Float? = null
        @Volatile private var selectedCaptureW: Int = 0
        @Volatile private var selectedCaptureH: Int = 0

        fun isWoodleForeground(): Boolean {
            val service = instance
            val rootPkg = try { service?.rootInActiveWindow?.packageName?.toString() } catch (_:Throwable) { null }
            return currentPackage == WOODLE_PACKAGE || rootPkg == WOODLE_PACKAGE
        }

        fun tap(x: Float, y: Float): Boolean = tapMapped(x,y,0,0)

        fun tapMapped(x: Float, y: Float, captureWidth:Int, captureHeight:Int): Boolean {
            val service = instance ?: return false
            if (!isWoodleForeground()) return false
            var tx=x; var ty=y
            if(captureWidth>0 && captureHeight>0){
                try{
                    val bounds=Rect(); service.rootInActiveWindow?.getBoundsInScreen(bounds)
                    if(bounds.width()>0 && bounds.height()>0){
                        tx=bounds.left + x/captureWidth.toFloat()*bounds.width()
                        ty=bounds.top + y/captureHeight.toFloat()*bounds.height()
                    }
                }catch(_:Throwable){}
            }
            val path=Path().apply{moveTo(tx,ty)}
            val stroke=GestureDescription.StrokeDescription(path,0,42)
            return service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(),null,null)
        }

        fun updateDebugOverlay(snapshot: DebugOverlayView.Snapshot?, selected: PuzzleDetector.Screw?) {
            val service = instance ?: return
            selectedX = selected?.x?.toFloat()
            selectedY = selected?.y?.toFloat()
            selectedCaptureW = snapshot?.captureWidth ?: 0
            selectedCaptureH = snapshot?.captureHeight ?: 0
            service.debugOverlay?.setSnapshot(snapshot)
            service.tapButton?.post {
                service.tapButton?.isEnabled = selected != null && isWoodleForeground()
                service.tapButton?.text = if (selected == null) "NO MOVE SELECTED" else "TAP SELECTED"
            }
        }
    }

    private var wm: WindowManager? = null
    internal var debugOverlay: DebugOverlayView? = null
    internal var tapButton: Button? = null

    override fun onServiceConnected(){
        super.onServiceConnected()
        instance=this
        showDebugWindows()
    }

    private fun showDebugWindows() {
        if (debugOverlay != null) return
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        debugOverlay = DebugOverlayView(this)
        val drawParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm?.addView(debugOverlay, drawParams)

        tapButton = Button(this).apply {
            text = "NO MOVE SELECTED"
            isEnabled = false
            alpha = .92f
            setOnClickListener {
                val x = selectedX
                val y = selectedY
                if (x != null && y != null && isWoodleForeground()) {
                    tapMapped(x, y, selectedCaptureW, selectedCaptureH)
                }
            }
        }
        val buttonParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 110
        }
        wm?.addView(tapButton, buttonParams)
    }

    private fun removeDebugWindows() {
        try { debugOverlay?.let { wm?.removeView(it) } } catch (_:Throwable) {}
        try { tapButton?.let { wm?.removeView(it) } } catch (_:Throwable) {}
        debugOverlay = null
        tapButton = null
        wm = null
    }

    override fun onAccessibilityEvent(event:AccessibilityEvent?){
        event?.packageName?.toString()?.takeIf{it.isNotBlank()}?.let{currentPackage=it}
        tapButton?.isEnabled = selectedX != null && isWoodleForeground()
    }
    override fun onInterrupt()=Unit
    override fun onDestroy(){
        removeDebugWindows()
        if(instance===this)instance=null
        currentPackage=null
        super.onDestroy()
    }
}
