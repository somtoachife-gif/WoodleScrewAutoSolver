package com.example.woodlesolver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class AutoTapAccessibilityService : AccessibilityService() {

    companion object {
        private const val WOODLE_PACKAGE = "com.wood.bolt.wordle.screw.nuts.puzzle"

        @Volatile
        var instance: AutoTapAccessibilityService? = null

        @Volatile
        private var currentPackage: String? = null

        fun isWoodleForeground(): Boolean = currentPackage == WOODLE_PACKAGE

        fun tap(x: Float, y: Float): Boolean {
            val service = instance ?: return false
            if (!isWoodleForeground()) return false

            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 45)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return service.dispatchGesture(gesture, null, null)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        if (!pkg.isNullOrBlank()) currentPackage = pkg
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        currentPackage = null
        super.onDestroy()
    }
}
