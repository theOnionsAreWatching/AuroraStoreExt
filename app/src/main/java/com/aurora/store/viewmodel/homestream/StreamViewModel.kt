/*
 * Aurora Store
 *  Copyright (C) 2021, Rahul Kumar Patel <whyorean@gmail.com>
 *
 *  Aurora Store is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  Aurora Store is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with Aurora Store.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.aurora.store.viewmodel.homestream

import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.gplayapi.data.models.StreamBundle
import com.aurora.gplayapi.data.models.StreamCluster
import com.aurora.gplayapi.helpers.AppDetailsHelper
import com.aurora.gplayapi.helpers.contracts.StreamContract
import com.aurora.gplayapi.helpers.web.WebStreamHelper
import com.aurora.store.HomeStash
import com.aurora.store.data.model.ViewState
import com.aurora.store.data.providers.WhitelistFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@HiltViewModel
class StreamViewModel @Inject constructor(
    private val webStreamHelper: WebStreamHelper,
    private val whitelistFilter: WhitelistFilter,
    private val appDetailsHelper: AppDetailsHelper
) : ViewModel() {

    private val TAG = StreamViewModel::class.java.simpleName

    val liveData: MutableLiveData<ViewState> = MutableLiveData()

    private val stash: HomeStash = mutableMapOf()

    private val streamContract: StreamContract
        get() = webStreamHelper

    // Mutex to protect stash access for thread safety
    private val stashMutex = Mutex()

    fun getStreamBundle(category: StreamContract.Category, type: StreamContract.Type) {
        liveData.postValue(ViewState.Loading)
        observe(category, type)
    }

    fun observe(category: StreamContract.Category, type: StreamContract.Type) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                stashMutex.withLock {
                    val bundle = targetBundle(category)

                    // If we already have a fallback bundle, just return it
                    if (bundle.hasCluster()) {
                        liveData.postValue(ViewState.Success(stash.toMap()))
                        return@withLock
                    }

                    // Always create fallback bundle as the default
                    Log.i(TAG, "Creating fallback bundle as default content")
                    try {
                        val fallbackBundle = createFallbackBundle(category)
                        stash[category] = fallbackBundle
                        liveData.postValue(ViewState.Success(stash.toMap()))
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create fallback bundle, trying API as backup", e)
                        
                        // Fallback to API if fallback creation fails
                        try {
                            val newBundle = streamContract.fetch(type, category)
                            val filteredNewClusters = newBundle.streamClusters.mapValues { (_, cluster) ->
                                cluster.copy(clusterAppList = whitelistFilter.filterApps(cluster.clusterAppList))
                            }
                            val mergedBundle = bundle.copy(
                                streamClusters = filteredNewClusters,
                                streamNextPageUrl = newBundle.streamNextPageUrl
                            )
                            stash[category] = mergedBundle
                            liveData.postValue(ViewState.Success(stash.toMap()))
                        } catch (apiException: Exception) {
                            Log.e(TAG, "Both fallback and API failed", apiException)
                            liveData.postValue(ViewState.Error("Unable to load content"))
                        }
                    }
                }
            } catch (e: Exception) {
                liveData.postValue(ViewState.Error(e.message))
            }
        }
    }

    fun observeCluster(category: StreamContract.Category, streamCluster: StreamCluster) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (streamCluster.hasNext()) {
                    val newCluster = streamContract.nextStreamCluster(
                        streamCluster.clusterNextPageUrl
                    )
                    
                    // Filter new cluster apps
                    val filteredCluster = newCluster.copy(
                        clusterAppList = whitelistFilter.filterApps(newCluster.clusterAppList)
                    )

                    stashMutex.withLock {
                        updateCluster(category, streamCluster.id, filteredCluster)
                    }

                    liveData.postValue(ViewState.Success(stash.toMap()))
                } else {
                    stashMutex.withLock {
                        postClusterEnd(category, streamCluster.id)
                    }

                    liveData.postValue(ViewState.Success(stash.toMap()))
                }
            } catch (e: Exception) {
                liveData.postValue(ViewState.Error(e.message))
            }
        }
    }

    private fun updateCluster(
        category: StreamContract.Category,
        clusterID: Int,
        newCluster: StreamCluster
    ) {
        val bundle = stash[category] ?: return
        val oldCluster = bundle.streamClusters[clusterID] ?: return

        val mergedCluster = oldCluster.copy(
            clusterNextPageUrl = newCluster.clusterNextPageUrl,
            clusterAppList = oldCluster.clusterAppList + newCluster.clusterAppList
        )

        val updatedClusters = bundle.streamClusters.toMutableMap().apply {
            this[clusterID] = mergedCluster
        }

        stash[category] = bundle.copy(streamClusters = updatedClusters)
    }

    private fun postClusterEnd(category: StreamContract.Category, clusterID: Int) {
        val bundle = stash[category] ?: return
        val oldCluster = bundle.streamClusters[clusterID] ?: return

        val updatedCluster = oldCluster.copy(clusterNextPageUrl = "")
        val updatedClusters = bundle.streamClusters.toMutableMap().apply {
            this[clusterID] = updatedCluster
        }

        stash[category] = bundle.copy(streamClusters = updatedClusters)
    }

    private fun targetBundle(category: StreamContract.Category): StreamBundle {
        return stash.getOrPut(category) { StreamBundle() }
    }

    /**
     * Creates a fallback bundle populated with whitelisted apps when API results are empty
     */
    private suspend fun createFallbackBundle(category: StreamContract.Category): StreamBundle {
        try {
            val whitelistedPackages = whitelistFilter.getAllowedPackages()
            val fallbackApps = mutableListOf<com.aurora.gplayapi.data.models.App>()
            
            // Fetch app details for each whitelisted package
            whitelistedPackages.forEach { packageName ->
                try {
                    val app = appDetailsHelper.getAppByPackageName(packageName)
                    fallbackApps.add(app)
                    Log.d(TAG, "Added fallback app: ${app.displayName} (${packageName})")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch details for whitelisted app: $packageName", e)
                }
            }
            
            // Create a fallback cluster
            val fallbackCluster = StreamCluster(
                id = 999,
                clusterTitle = "Recommended Apps",
                clusterSubtitle = "Apps available in your organization",
                clusterBrowseUrl = "",
                clusterNextPageUrl = "",
                clusterAppList = fallbackApps
            )
            
            return StreamBundle(
                streamTitle = "Recommended",
                streamClusters = mapOf(999 to fallbackCluster)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create fallback bundle", e)
            return StreamBundle()
        }
    }
}
