package android.app

import android.content.Context
import android.view.View
import widgethost.Host

open class Activity : Context() { fun setContentView(root: View) = Host.install(root) }
