package com.code2hack.eyeprojector.directhost.scale

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.code2hack.eyeprojector.directhost.PhoneSessionController
import java.io.Closeable

/** Closing or recreating the Phone gesture panel never stops projection. */
class AppScaleActivity : Activity() {
    private val attachmentOwner = Any()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var coordinator: AppScaleCoordinator
    private lateinit var panel: ScalePanelView
    private var target: AppScaleTarget? = null
    private var subscription: Closeable? = null
    private val tick = object : Runnable {
        override fun run() { target?.let { coordinator.poll(it, attachmentOwner) }; main.postDelayed(this, 250) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coordinator = PhoneSessionController.get(this).appScales
        target = coordinator.state()?.target?.takeIf {
            it.id == intent.getStringExtra(TARGET) && it.packageName == intent.getStringExtra(PACKAGE)
        }
        panel = ScalePanelView(this, { factor -> target?.let { coordinator.pinch(it, factor, attachmentOwner) } }, { finish() })
        setContentView(panel)
    }
    override fun onStart() {
        super.onStart()
        target?.let { coordinator.attach(it, attachmentOwner) }
        subscription = coordinator.observe { state -> panel.render(state?.takeIf { it.target == target }) }
        main.post(tick)
    }
    override fun onStop() {
        main.removeCallbacks(tick)
        subscription?.close(); subscription = null
        target?.let { coordinator.detach(it, attachmentOwner) }
        super.onStop()
    }

    companion object {
        private const val TARGET = "app-scale-intent"
        private const val PACKAGE = "app-scale-package"
        fun intent(context: Context, target: AppScaleTarget): Intent = Intent(context, AppScaleActivity::class.java)
            .putExtra(TARGET, target.id).putExtra(PACKAGE, target.packageName)
    }
}
