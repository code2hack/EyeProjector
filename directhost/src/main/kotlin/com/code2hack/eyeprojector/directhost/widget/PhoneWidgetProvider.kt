package com.code2hack.eyeprojector.directhost.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

/** Receivers render bounded snapshots; ongoing reconciliation belongs to the existing app owner. */
class PhoneWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) = PhoneWidgetUpdates.update(context)
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = PhoneWidgetUpdates.update(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = PhoneWidgetUpdates.update(context)
    override fun onDeleted(context: Context, ids: IntArray) = PhoneWidgetUpdates.removed()
    override fun onDisabled(context: Context) = PhoneWidgetUpdates.disabled()
    override fun onRestored(context: Context, oldIds: IntArray, newIds: IntArray) = PhoneWidgetUpdates.update(context)
}
