package com.code2hack.eyeprojector.directhost.anchor.preferences

import android.content.Context
import com.code2hack.eyeprojector.directhost.anchor.target.AnchorTargetDescriptor
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Host-only boundary test for actual Android adapter. Android Context and Log are doubled. */
class AppAnchorPreferencesHostTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun activityAndFutureOwnerShareApplicationInstanceStorageAndEvents() {
        val application = Context(temporary.root)
        val activity = object : Context(File(temporary.root, "must-not-be-used")) {
            override val applicationContext: Context get() = application
            override val filesDir: File get() = throw AssertionError("Retained Activity instead of application Context")
        }
        val menuStore = AppAnchorPreferences.open(activity)
        val ownerStore = AppAnchorPreferences.open(application)
        assertSame(menuStore, ownerStore)
        val anchor = AppAnchor(AnchorTargetDescriptor("host.example", null, 1, "host.example:id/action", null), AnchorMode.HOLD)
        val events = mutableListOf<AppAnchorChange>()
        val subscription = ownerStore.observe(anchor.packageName, events::add)
        try {
            menuStore.replace(anchor)
            assertEquals(anchor, ownerStore.read(anchor.packageName))
            val reopenedDisk = AppAnchorStore(FileAppAnchorStorage(File(temporary.root, "projection_app_anchors")))
            assertEquals(anchor, reopenedDisk.read(anchor.packageName))
            menuStore.clear(anchor.packageName)
            assertNull(reopenedDisk.read(anchor.packageName))
            assertEquals(listOf(AppAnchorChange.Replaced(null, anchor, events.first().context),
                AppAnchorChange.Cleared(anchor, events.last().context)), events)
        } finally { subscription.close() }
    }
}
