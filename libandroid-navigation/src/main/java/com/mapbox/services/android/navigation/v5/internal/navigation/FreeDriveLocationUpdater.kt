package com.mapbox.services.android.navigation.v5.internal.navigation

import android.location.Location
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.mapbox.android.core.location.LocationEngine
import com.mapbox.android.core.location.LocationEngineCallback
import com.mapbox.android.core.location.LocationEngineRequest
import com.mapbox.android.core.location.LocationEngineResult
import com.mapbox.navigator.NavigationStatus
import com.mapbox.services.android.navigation.v5.navigation.OfflineNavigator
import com.mapbox.services.android.navigation.v5.navigation.OnOfflineTilesConfiguredCallback
import java.lang.ref.WeakReference
import java.util.Date
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import timber.log.Timber

internal class FreeDriveLocationUpdater(
    private var locationEngine: LocationEngine,
    private var locationEngineRequest: LocationEngineRequest,
    private val navigationEventDispatcher: NavigationEventDispatcher,
    private val mapboxNavigator: MapboxNavigator,
    private val offlineNavigator: OfflineNavigator,
    private val executorService: ScheduledExecutorService
) {
    private val callback = CurrentLocationEngineCallback(this)
    private var future: ScheduledFuture<*>? = null
    private var electronicHorizonFuture: ScheduledFuture<*>? = null
    private var rawLocation: Location? = null
    private val handler = Handler(Looper.getMainLooper())
    private val cachedLocations = mutableListOf<Location>()

    companion object {
        private const val ELECTRONIC_HORIZON_DELAY = 20_000L
        private const val ELECTRONIC_HORIZON_INTERVAL = 20_000L
        private const val LOCATIONS_CACHE_MAX_SIZE = 5
    }

    enum class ElectronicHorizonExpansion (val value: String) {
        _1D("1D"),
        _1_5D("1.5D"),
        _2D("2D")
    }

    fun configure(
        tilePath: String,
        onOfflineTilesConfiguredCallback: OnOfflineTilesConfiguredCallback
    ) {
        offlineNavigator.configure(tilePath, onOfflineTilesConfiguredCallback)
    }

    fun start() {
        if (future == null) {
            locationEngine.requestLocationUpdates(locationEngineRequest, callback, null)
            future = executorService.scheduleAtFixedRate({
                if (rawLocation != null) {
                    // Pass the same lag as when in active guidance i.e. 1500 ms
                    val enhancedLocation = getLocation(Date(), 1500, rawLocation)
                    handler.post {
                        navigationEventDispatcher.onEnhancedLocationUpdate(
                            enhancedLocation
                        )
                    }
                }
            }, 1500, 1000, TimeUnit.MILLISECONDS)
        }

        if (electronicHorizonFuture == null) {
            electronicHorizonFuture = executorService.scheduleAtFixedRate({
                val request = buildElectronicHorizonRequest(ElectronicHorizonExpansion._1D)
                Timber.d("electronicHorizonFuture request = $request")
                val routerResult = mapboxNavigator.retrieveElectronicHorizon(request)
                Timber.d("electronicHorizonFuture routerResult success = ${routerResult.success}, json = ${routerResult.json}")
            }, ELECTRONIC_HORIZON_DELAY, ELECTRONIC_HORIZON_INTERVAL, TimeUnit.MILLISECONDS)
        }
    }

    fun stop() {
        future?.let {
            stopLocationUpdates()
        }

        stopRetrieveElectronicHorizon()
    }

    fun kill() {
        future?.let {
            stopLocationUpdates()
        }
        stopRetrieveElectronicHorizon()
        executorService.shutdown()
    }

    fun updateLocationEngine(locationEngine: LocationEngine) {
        val currentFuture = future
        stop()
        this.locationEngine = locationEngine
        currentFuture?.let {
            start()
        }
    }

    fun updateLocationEngineRequest(request: LocationEngineRequest) {
        val currentFuture = future
        stop()
        this.locationEngineRequest = request
        currentFuture?.let {
            start()
        }
    }

    private fun getLocation(date: Date, lagMillis: Long, rawLocation: Location?): Location {
        val status = mapboxNavigator.retrieveStatus(date, lagMillis)
        return getMapMatchedLocation(status, rawLocation)
    }

    private fun getMapMatchedLocation(
        status: NavigationStatus,
        fallbackLocation: Location?
    ): Location {
        val snappedLocation = Location(fallbackLocation)
        snappedLocation.provider = "enhanced"
        val fixLocation = status.location
        val coordinate = fixLocation.coordinate
        snappedLocation.latitude = coordinate.latitude()
        snappedLocation.longitude = coordinate.longitude()
        fixLocation.bearing?.let { snappedLocation.bearing = it }
        snappedLocation.time = fixLocation.time.time
        return snappedLocation
    }

    private fun stopLocationUpdates() {
        locationEngine.removeLocationUpdates(callback)
        future?.cancel(false)
        future = null
    }

    private fun stopRetrieveElectronicHorizon() {
        electronicHorizonFuture?.cancel(false)
        electronicHorizonFuture = null
        cachedLocations.clear()
    }

    private fun onLocationChanged(location: Location?) {
        location?.let { currentLocation ->
            rawLocation = currentLocation
            cacheLocation(currentLocation)
            executorService.execute {
                mapboxNavigator.updateLocation(currentLocation)
            }
        }
    }

    private fun cacheLocation(location: Location) {
        // remove the oldest location to fit the max cache size
        if (cachedLocations.size == LOCATIONS_CACHE_MAX_SIZE) {
            cachedLocations.removeAt(0)
        }

        cachedLocations.add(location)
    }

    private fun buildElectronicHorizonRequest(expansion: ElectronicHorizonExpansion): String {
        val positions = cachedLocations.map { Position(it.latitude, it.longitude) }
        val options = mapOf("expansion" to expansion.value)
        val request = ElectronicHorizonRequest(positions, options)

        return Gson().toJson(request)
    }

    private class CurrentLocationEngineCallback(locationUpdater: FreeDriveLocationUpdater) :
        LocationEngineCallback<LocationEngineResult> {

        private val updaterWeakReference: WeakReference<FreeDriveLocationUpdater> =
            WeakReference(locationUpdater)

        override fun onSuccess(result: LocationEngineResult) {
            val location = result.lastLocation
            updaterWeakReference.get()?.onLocationChanged(location)
        }

        override fun onFailure(exception: Exception) {
            Timber.e(exception)
        }
    }

    private data class Position (
        val lat: Double,
        val lon: Double)

    private data class ElectronicHorizonRequest (
        val shape: List<Position>,
        val eh_options: Map<String, Any>
    )
}
