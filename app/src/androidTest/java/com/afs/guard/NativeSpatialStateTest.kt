package com.afs.guard

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.afs.guard.style.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class NativeSpatialStateTest {
    @Test fun settingsObservesPointerWithoutReplacingContentAndRestoresTransforms() = verify(SettingsActivity::class.java)
    @Test fun freezeObservesPointerWithoutReplacingContentAndRestoresTransforms() = verify(FreezeBoardActivity::class.java)
    private fun verify(type: Class<out ThemedActivity>) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val bridge=StylePreferencesBridge.get(context)
        runBlocking { withTimeout(10000) { bridge.ready.first { it } } }
        val style=StyleManager.selected(context); val quality=EffectQualityManager.preference(context)
        val prefs=context.getSharedPreferences(Ui.SP,0);val reduced=prefs.getBoolean("reduce_motion",false)
        val rules=RuleStore.exportRules(context)
        fun main(work:()->Unit)=instrumentation.runOnMainSync(work)
        fun card(view:View):View? {
            if(view.getTag(R.id.visual_card)==true&&view.width>0&&view.getGlobalVisibleRect(Rect()))return view
            if(view is ViewGroup)for(i in 0 until view.childCount)card(view.getChildAt(i))?.let{return it}
            return null
        }
        try {
            val spatial=StyleRegistry.all().first { it.create() is SpatialStyle }.id()
            main { prefs.edit().putBoolean("reduce_motion",false).commit();EffectQualityManager.setPreference(context,"high");StyleManager.select(context,spatial) }
            ActivityScenario.launch(type).use { scenario ->
                lateinit var page:ThemedActivity;lateinit var target:View;var content:Any?=null
                var originalX=0f;var originalY=0f;var camera=0f
                scenario.onActivity { page=it }
                val deadline=SystemClock.uptimeMillis()+10000
                var ready=false
                while(!ready&&SystemClock.uptimeMillis()<deadline){
                    main { card(page.window.decorView)?.let { target=it;ready=true } };if(!ready)SystemClock.sleep(50)
                }
                assertTrue("No visible semantic card",ready)
                main {
                    content=page.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                    originalX=target.rotationX;originalY=target.rotationY;camera=target.cameraDistance
                    val bounds=Rect();assertTrue(target.getGlobalVisibleRect(bounds))
                    val time=SystemClock.uptimeMillis()
                    MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,bounds.left+bounds.width()*.8f,bounds.top+bounds.height()*.2f,0).also { page.dispatchTouchEvent(it);it.recycle() }
                    assertTrue(target.rotationX!=originalX||target.rotationY!=originalY)
                    MotionEvent.obtain(time,time+10,MotionEvent.ACTION_CANCEL,bounds.exactCenterX(),bounds.exactCenterY(),0).also { page.dispatchTouchEvent(it);it.recycle() }
                }
                SystemClock.sleep(850)
                main {
                    assertEquals(originalX,target.rotationX,.001f);assertEquals(originalY,target.rotationY,.001f)
                    assertEquals(camera,target.cameraDistance,.001f)
                    assertSame(content,page.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
                    StyleManager.select(context,"paper")
                }
                assertEquals(rules,RuleStore.exportRules(context))
            }
        } finally {
            main {StyleManager.select(context,style);EffectQualityManager.setPreference(context,quality);prefs.edit().putBoolean("reduce_motion",reduced).commit()}
            runBlocking { withTimeout(10000) { bridge.awaitWrites() } }
        }
    }
}
