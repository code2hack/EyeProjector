package com.code2hack.eyeprojector.directhost.apps

import com.code2hack.eyeprojector.directhost.notifications.AppNotificationStorage
import com.code2hack.eyeprojector.directhost.notifications.AppNotificationStore
import com.code2hack.eyeprojector.directhost.scale.AppScaleStorage
import com.code2hack.eyeprojector.directhost.scale.AppScaleStore
import org.junit.Assert.*
import org.junit.Test

class ChosenAppStoreTest {
    private class Notifications : AppNotificationStorage {
        val values = mutableMapOf<String, Boolean>()
        override fun read(packageName: String) = values[packageName]
        override fun write(packageName: String, enabled: Boolean) { values[packageName] = enabled }
    }

    @Test fun notificationsDefaultOnWithoutReplacingExplicitChoices() {
        val storage = Notifications()
        val preferences = AppNotificationStore(storage)
        assertTrue(preferences.isEnabled("example.new"))
        assertTrue(storage.values.isEmpty()) // The default is not an explicit saved choice.
        preferences.setEnabled("example.off", false)
        preferences.setEnabled("example.on", true)
        assertEquals(mapOf("example.off" to false, "example.on" to true), storage.values)
        val reopened = AppNotificationStore(storage)
        assertFalse(reopened.isEnabled("example.off"))
        assertTrue(reopened.isEnabled("example.on"))
        assertTrue(reopened.isEnabled("example.new"))
        reopened.setEnabled("example.off", true)
        reopened.setEnabled("example.on", false)
        assertTrue(preferences.isEnabled("example.off")) // Existing consumers read the current choice.
        assertFalse(preferences.isEnabled("example.on"))
    }

    @Test fun notificationChoicesLeaveChosenOrderAndScaleIntactAcrossReloadAndReadd() {
        val selection = Storage()
        val apps = ChosenAppStore(selection)
        val alpha = InstalledApp("example.alpha", "Alpha")
        val beta = InstalledApp("example.beta", "Beta")
        apps.add(beta); apps.add(alpha)
        val saved = apps.read()
        val scales = mutableMapOf("example.alpha" to 1.5, "example.beta" to .75)
        val scaleStore = AppScaleStore(object : AppScaleStorage {
            override fun read(packageName: String) = scales[packageName]
            override fun write(packageName: String, scale: Double): Boolean {
                scales[packageName] = scale
                return true
            }
        })
        val storage = Notifications()
        AppNotificationStore(storage).setEnabled(alpha.packageName, false)
        AppNotificationStore(storage).setEnabled(beta.packageName, true)
        assertEquals(saved, ChosenAppStore(selection).entries(Catalog(listOf(alpha, beta))).map { it.app })
        assertEquals(1.5, scaleStore.read(alpha.packageName), 0.0)
        assertEquals(.75, scaleStore.read(beta.packageName), 0.0)
        apps.remove(alpha.packageName)
        apps.add(alpha.copy(label = "New launcher label"))
        assertEquals(listOf(beta.packageName, alpha.packageName), apps.read().map { it.packageName })
        assertFalse(AppNotificationStore(storage).isEnabled(alpha.packageName))
        assertTrue(AppNotificationStore(storage).isEnabled(beta.packageName))
    }

    private class Storage : ChosenAppStorage {
        private var apps = emptyList<ChosenApp>()
        override fun read() = apps.toList()
        override fun write(apps: List<ChosenApp>) { this.apps = apps.toList() }
    }

    private class Catalog(var apps: List<InstalledApp>) : AppCatalog {
        var invisiblePackages = emptySet<String>()
        override fun launchableApps() = apps.filter { it.launchable && it.packageName !in invisiblePackages }
        override fun find(packageName: String) = apps.firstOrNull {
            it.packageName == packageName && it.packageName !in invisiblePackages
        }
    }

    @Test fun emptyInstallHasNoInferredOrPreselectedApps() {
        assertTrue(ChosenAppStore(Storage()).read().isEmpty())
    }

    @Test fun addDeduplicatesPackagesAndPreservesOrderAcrossStoreRecreation() {
        val storage = Storage()
        val first = ChosenAppStore(storage)
        first.add(InstalledApp("example.beta", "Beta"))
        first.add(InstalledApp("example.alpha", "Alpha"))
        first.add(InstalledApp("example.beta", "Beta alias"))
        assertEquals(listOf(ChosenApp("example.beta", "Beta"), ChosenApp("example.alpha", "Alpha")),
            ChosenAppStore(storage).read())
    }

    @Test fun removeEditsOnlyTheSelectionAndReaddingAppAppendsIt() {
        val catalog = Catalog(listOf(InstalledApp("example.one", "One"), InstalledApp("example.two", "Two")))
        val store = ChosenAppStore(Storage())
        catalog.apps.forEach(store::add)
        store.remove("example.one")
        assertEquals(listOf("example.two"), store.read().map { it.packageName })
        assertEquals(2, catalog.apps.size)
        assertEquals(listOf("example.one"), store.candidates(catalog).map { it.packageName })
        store.add(catalog.apps.first())
        assertEquals(listOf("example.two", "example.one"), store.read().map { it.packageName })
    }

    @Test fun absentOrInvisibleLookupRetainsSavedPackageLabelAndOrderAsUnavailable() {
        val store = ChosenAppStore(Storage())
        val apps = listOf(InstalledApp("example.first", "First"), InstalledApp("example.middle", "Saved label"),
            InstalledApp("example.last", "Last"))
        apps.forEach(store::add)
        val saved = store.read()
        val catalog = Catalog(apps.filterNot { it.packageName == "example.middle" })
        fun assertUnresolved() {
            assertNull(catalog.find("example.middle"))
            val entries = store.entries(catalog)
            assertEquals(listOf(AppAvailability.AVAILABLE, AppAvailability.UNAVAILABLE, AppAvailability.AVAILABLE),
                entries.map { it.availability })
            assertEquals(saved, entries.map { it.app })
            assertEquals(saved, store.read())
        }
        assertUnresolved() // Actually absent from the simulated installed catalog.
        catalog.apps = apps
        catalog.invisiblePackages = setOf("example.middle")
        assertEquals(3, catalog.apps.size) // Still installed, but the lookup cannot observe it.
        assertUnresolved()
    }

    @Test fun visibleDisabledAppIsUnavailableAndReenablingPreservesItsChoice() {
        val store = ChosenAppStore(Storage())
        store.add(InstalledApp("example.one", "Original label"))
        val saved = store.read()
        val catalog = Catalog(listOf(InstalledApp("example.one", "Updated label", false)))
        assertEquals(AppAvailability.UNAVAILABLE, store.entries(catalog).single().availability)
        assertEquals("Updated label", store.entries(catalog).single().app.label)
        assertEquals(saved, store.read())
        catalog.apps = listOf(InstalledApp("example.one", "Updated label"))
        assertEquals(AppAvailability.AVAILABLE, store.entries(catalog).single().availability)
        assertEquals(saved, store.read())
    }

    @Test fun restoringVisibilityOrReinstallingRestoresAvailabilityWithoutReordering() {
        val store = ChosenAppStore(Storage())
        val apps = listOf(InstalledApp("example.first", "First"), InstalledApp("example.middle", "Saved label"),
            InstalledApp("example.last", "Last"))
        apps.forEach(store::add)
        val saved = store.read()
        val catalog = Catalog(apps)
        catalog.invisiblePackages = setOf("example.middle")
        assertEquals(AppAvailability.UNAVAILABLE, store.entries(catalog)[1].availability)
        catalog.invisiblePackages = emptySet()
        assertEquals(AppAvailability.AVAILABLE, store.entries(catalog)[1].availability)
        catalog.apps = apps.filterNot { it.packageName == "example.middle" }
        assertEquals(AppAvailability.UNAVAILABLE, store.entries(catalog)[1].availability)
        catalog.apps = apps.map { if (it.packageName == "example.middle") it.copy(label = "Updated label") else it }
        assertEquals(listOf(AppAvailability.AVAILABLE, AppAvailability.AVAILABLE, AppAvailability.AVAILABLE),
            store.entries(catalog).map { it.availability })
        assertEquals(saved.map { it.packageName }, store.entries(catalog).map { it.app.packageName })
        assertEquals("Updated label", store.entries(catalog)[1].app.label)
        assertEquals(saved, store.read())
    }

    @Test fun pickerSortsLabelsWithPackageTieBreakAndOmitsChosenAndNonlaunchableApps() {
        val store = ChosenAppStore(Storage())
        store.add(InstalledApp("example.chosen", "Chosen"))
        val catalog = Catalog(listOf(
            InstalledApp("example.z", "alpha"), InstalledApp("example.a", "Alpha"),
            InstalledApp("example.chosen", "Chosen"), InstalledApp("example.disabled", "Disabled", false),
            InstalledApp("example.z", "alpha alias"), InstalledApp("example.beta", "Beta"),
        ))
        assertEquals(listOf("example.a", "example.z", "example.beta"), store.candidates(catalog).map { it.packageName })
    }

    @Test fun twoConsumersSeeOneSelectionWithoutStaleCopies() {
        val storage = Storage()
        val phone = ChosenAppStore(storage)
        val home = ChosenAppStore(storage)
        phone.add(InstalledApp("example.one", "One"))
        home.add(InstalledApp("example.two", "Two"))
        phone.remove("example.one")
        assertEquals(listOf(ChosenApp("example.two", "Two")), home.read())
    }
}
