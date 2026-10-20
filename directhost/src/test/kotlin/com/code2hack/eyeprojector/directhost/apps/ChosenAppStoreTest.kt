package com.code2hack.eyeprojector.directhost.apps

import org.junit.Assert.*
import org.junit.Test

class ChosenAppStoreTest {
    private class Storage : ChosenAppStorage {
        private var apps = emptyList<ChosenApp>()
        override fun read() = apps.toList()
        override fun write(apps: List<ChosenApp>) { this.apps = apps.toList() }
    }

    private class Catalog(var apps: List<InstalledApp>) : AppCatalog {
        override fun launchableApps() = apps.filter { it.launchable }
        override fun find(packageName: String) = apps.firstOrNull { it.packageName == packageName }
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

    @Test fun uninstallAndDisableRemainDistinctWithoutDestroyingSavedChoices() {
        val store = ChosenAppStore(Storage())
        store.add(InstalledApp("example.one", "Original label"))
        val catalog = Catalog(emptyList())
        assertEquals(AppAvailability.NOT_INSTALLED, store.entries(catalog).single().availability)
        assertEquals("Original label", store.entries(catalog).single().app.label)
        catalog.apps = listOf(InstalledApp("example.one", "Updated label", false))
        assertEquals(AppAvailability.UNAVAILABLE, store.entries(catalog).single().availability)
        catalog.apps = listOf(InstalledApp("example.one", "Updated label"))
        assertEquals(AppAvailability.AVAILABLE, store.entries(catalog).single().availability)
        assertEquals("Updated label", store.entries(catalog).single().app.label)
        assertEquals(1, store.read().size)
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
