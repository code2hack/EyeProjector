package android.view.accessibility

class AccessibilityEvent

class AccessibilityManager {
    val enabled = mutableListOf<android.accessibilityservice.AccessibilityServiceInfo>()
    var reads = 0; private set
    fun getEnabledAccessibilityServiceList(mask: Int): List<android.accessibilityservice.AccessibilityServiceInfo> {
        reads++
        return enabled.toList()
    }
}
