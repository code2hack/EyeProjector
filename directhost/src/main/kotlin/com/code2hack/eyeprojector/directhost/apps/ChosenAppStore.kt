package com.code2hack.eyeprojector.directhost.apps

/** Package identity is stable across launcher-activity changes and app updates. */
data class ChosenApp(val packageName: String, val label: String)

data class InstalledApp(val packageName: String, val label: String, val launchable: Boolean = true)

interface AppCatalog {
    /** One entry per launchable package, including apps with multiple launcher activities. */
    fun launchableApps(): List<InstalledApp>
    fun find(packageName: String): InstalledApp?
}

enum class AppAvailability { AVAILABLE, NOT_INSTALLED, UNAVAILABLE }

data class ChosenAppEntry(val app: ChosenApp, val availability: AppAvailability)

interface ChosenAppStorage {
    fun read(): List<ChosenApp>
    fun write(apps: List<ChosenApp>)
}

/**
 * The ordered selection used by both the Phone panel and the projected home.
 * Reading the catalog never removes a saved choice: reinstalling an app restores its icon.
 * Removal writes only this store, without any package-management or app-data operation.
 */
class ChosenAppStore(private val storage: ChosenAppStorage) {
    fun read(): List<ChosenApp> = synchronized(lock) { storage.read().toList() }

    fun add(app: InstalledApp) = synchronized(lock) {
        val current = storage.read()
        if (current.none { it.packageName == app.packageName }) {
            storage.write(current + ChosenApp(app.packageName, app.label))
        }
    }

    fun remove(packageName: String) = synchronized(lock) {
        val current = storage.read()
        val next = current.filterNot { it.packageName == packageName }
        if (next != current) storage.write(next)
    }

    fun entries(catalog: AppCatalog): List<ChosenAppEntry> = read().map { saved ->
        val installed = catalog.find(saved.packageName)
        ChosenAppEntry(
            if (installed == null) saved else saved.copy(label = installed.label),
            when {
                installed == null -> AppAvailability.NOT_INSTALLED
                installed.launchable -> AppAvailability.AVAILABLE
                else -> AppAvailability.UNAVAILABLE
            },
        )
    }

    fun candidates(catalog: AppCatalog): List<InstalledApp> {
        val selected = read().map { it.packageName }.toSet()
        return catalog.launchableApps().filter { it.launchable && it.packageName !in selected }
            .distinctBy { it.packageName }
            .sortedWith(compareBy<InstalledApp> { it.label.lowercase(java.util.Locale.ROOT) }.thenBy { it.packageName })
    }

    companion object {
        // Multiple UI consumers can open the same persistence file in this application process.
        private val lock = Any()
    }
}
