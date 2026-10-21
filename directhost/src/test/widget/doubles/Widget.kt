package android.appwidget
import android.content.Context
import android.content.ComponentName
import android.os.Bundle
import android.widget.RemoteViews
open class AppWidgetProvider {
    open fun onEnabled(context: Context) {}
    open fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {}
    open fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {}
    open fun onDeleted(context: Context, ids: IntArray) {}
    open fun onDisabled(context: Context) {}
    open fun onRestored(context: Context, oldIds: IntArray, newIds: IntArray) {}
}
class AppWidgetManager {
    var ids = intArrayOf(41, 42)
    val updates = mutableMapOf<Int, RemoteViews>()
    var failUpdates = false
    fun getAppWidgetIds(component: ComponentName) = ids
    fun updateAppWidget(id: Int, views: RemoteViews) { check(!failUpdates) { "Modeled launcher/render failure" }; updates[id] = views }
    companion object { val manager = AppWidgetManager(); fun getInstance(context: Context) = manager }
}
