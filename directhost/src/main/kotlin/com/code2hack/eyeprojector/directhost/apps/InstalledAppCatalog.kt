package com.code2hack.eyeprojector.directhost.apps

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/** Uses the manifest's MAIN/LAUNCHER visibility query, without a package-name allowlist. */
@Suppress("DEPRECATION") // These overloads also run on the minimum supported API 32.
class InstalledAppCatalog(private val packages: PackageManager) : AppCatalog {
    private fun launcherIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    override fun launchableApps(): List<InstalledApp> =
        packages.queryIntentActivities(launcherIntent(), 0)
            .map { it.activityInfo.applicationInfo }
            .filter { it.enabled }
            .distinctBy { it.packageName }
            .map { InstalledApp(it.packageName, it.loadLabel(packages).toString()) }

    override fun find(packageName: String): InstalledApp? = try {
        val info = packages.getApplicationInfo(packageName, 0)
        val launchable = info.enabled && packages.queryIntentActivities(
            launcherIntent().setPackage(packageName), 0,
        ).isNotEmpty()
        InstalledApp(packageName, info.loadLabel(packages).toString(), launchable)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    fun icon(packageName: String): Drawable = try {
        packages.getApplicationIcon(packageName)
    } catch (_: PackageManager.NameNotFoundException) {
        packages.defaultActivityIcon
    }
}
