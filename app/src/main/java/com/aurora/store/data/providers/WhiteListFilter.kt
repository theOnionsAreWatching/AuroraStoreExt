package com.aurora.store.data.providers

import android.content.Context
import com.aurora.gplayapi.data.models.App
import com.aurora.store.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WhitelistFilter @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val whitelistedPackages by lazy {
        context.resources.getStringArray(R.array.allowed_packages).toSet()
    }

    /**
     * Filters a list of apps to only include whitelisted packages
     */
    fun filterApps(apps: List<App>): List<App> {
        return apps.filter { whitelistedPackages.contains(it.packageName) }
    }

    /**
     * Checks if a package is in the whitelist
     */
    fun isWhitelisted(packageName: String): Boolean {
        return whitelistedPackages.contains(packageName)
    }

    /**
     * Returns the set of allowed packages
     */
    fun getAllowedPackages(): Set<String> {
        return whitelistedPackages
    }
}
