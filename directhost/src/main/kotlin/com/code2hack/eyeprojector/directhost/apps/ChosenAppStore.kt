package com.code2hack.eyeprojector.directhost.apps

/** Package identity is stable across launcher-activity changes and app updates. */
data class ChosenApp(val packageName: String, val label: String)

data class InstalledApp(val packageName: String, val label: String, val launchable: Boolean = true)

interface AppCatalog {
    /** One entry per launchable package, including apps with multiple launcher activities. */
    fun launchableApps(): List<InstalledApp>
    /** Null means not observable by this catalog, including missing or visibility-filtered apps. */
    fun find(packageName: String): InstalledApp?
}

enum class AppAvailability { AVAILABLE, UNAVAILABLE }

data class ChosenAppEntry(val app: ChosenApp, val availability: AppAvailability)

interface ChosenAppStorage {
    fun read(): List<ChosenApp>
    fun write(apps: List<ChosenApp>)
}
interface ChosenAppMutation {
    fun isCurrent(): Boolean
    fun finished(success: Boolean)
}

/**
 * The ordered selection used by both the Phone panel and the projected home.
 * Reading the catalog never removes a saved choice: reinstalling an app restores its icon.
 * Removal writes only this store, without any package-management or app-data operation.
 */
class ChosenAppStore(private val storage: ChosenAppStorage,
    private val beforeMutation: ((String, Boolean) -> ChosenAppMutation)? = null) {
    fun read(): List<ChosenApp> = synchronized(lock) { storage.read().toList() }

    fun add(app: InstalledApp) = mutate(app.packageName, true) { mutation ->
        synchronized(lock) {
            if (mutation?.isCurrent() == false) return@synchronized
            val current = storage.read()
            if (mutation?.isCurrent() == false) return@synchronized
            if (current.none { it.packageName == app.packageName }) storage.write(current + ChosenApp(app.packageName, app.label))
        }
    }

    fun remove(packageName: String) = mutate(packageName, false) { mutation ->
        synchronized(lock) {
            if (mutation?.isCurrent() == false) return@synchronized
            val current = storage.read()
            if (mutation?.isCurrent() == false) return@synchronized
            val next = current.filterNot { it.packageName == packageName }
            if (next != current) storage.write(next)
        }
    }

    private inline fun mutate(packageName: String, chosen: Boolean, operation: (ChosenAppMutation?) -> Unit) {
        // Intent precedes even waiting for the store lock/read. No I/O under policy authority.
        val mutation = beforeMutation?.invoke(packageName, chosen)
        var success = false
        try { operation(mutation); success = true } finally { mutation?.finished(success) }
    }

    fun entries(catalog: AppCatalog): List<ChosenAppEntry> = read().map { saved ->
        val observed = catalog.find(saved.packageName)
        ChosenAppEntry(
            if (observed == null) saved else saved.copy(label = observed.label),
            // A failed package lookup cannot distinguish removal from lost package visibility.
            if (observed?.launchable == true) AppAvailability.AVAILABLE else AppAvailability.UNAVAILABLE,
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
