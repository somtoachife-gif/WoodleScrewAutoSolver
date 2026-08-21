package com.example.woodlesolver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent

class AutoTapAccessibilityService : AccessibilityService() {
    companion object {
        private const val WOODLE_PACKAGE = "com.wood.bolt.wordle.screw.nuts.puzzle"
        @Volatile var instance: AutoTapAccessibilityService? = null
        @Volatile private var currentPackage: String? = null

        fun isWoodleForeground(): Boolean {
            val service = instance
            val rootPkg = try { service?.rootInActiveWindow?.packageName?.toString() } catch (_:Throwable) { null }
            return currentPackage == WOODLE_PACKAGE || rootPkg == WOODLE_PACKAGE
        }

        fun tap(x: Float, y: Float): Boolean = tapMapped(x,y,0,0)

        /** Maps MediaProjection coordinates into the active-window coordinate space. */
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
    }

    override fun onServiceConnected(){ super.onServiceConnected(); instance=this }
    override fun onAccessibilityEvent(event:AccessibilityEvent?){ event?.packageName?.toString()?.takeIf{it.isNotBlank()}?.let{currentPackage=it} }
    override fun onInterrupt()=Unit
    override fun onDestroy(){ if(instance===this)instance=null; currentPackage=null; super.onDestroy() }
}
