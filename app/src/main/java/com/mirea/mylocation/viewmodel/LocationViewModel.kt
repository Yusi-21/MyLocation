package com.mirea.mylocation

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LocationViewModel(application: Application) : AndroidViewModel(application) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(application)

    private val _locationState = MutableStateFlow<LocationState>(LocationState.Initial)
    val locationState: StateFlow<LocationState> = _locationState.asStateFlow()

    private val geocoder = Geocoder(application, Locale.getDefault())

    fun getCurrentLocation() {
        // check permission grant
        if (!hasLocationPermission()) {
            _locationState.update {
                LocationState.Error("There is no geolocation permission")
            }
            return
        }

        _locationState.update { LocationState.Loading }

        viewModelScope.launch {
            try {
                // trying to get new location with timeout 10 seconds
                val location = withTimeoutOrNull(10000L) {
                    getFreshLocation()
                }

                if (location != null) {
                    // received coordinates, do reverse geocoding
                    val address = getAddressFromLocation(location.latitude, location.longitude)

                    _locationState.update {
                        LocationState.Success(
                            address = address?.getAddressLine(0) ?: "Address not found",
                            latitude = location.latitude,
                            longitude = location.longitude,
                            fullAddress = address
                        )
                    }
                } else {
                    // try to get last location
                    getLastLocation()
                }
            } catch (e: SecurityException) {
                _locationState.update {
                    LocationState.Error("Geolocation permission is missingю")
                }
            } catch (e: Exception) {
                _locationState.update {
                    LocationState.Error("Error: ${e.localizedMessage ?: "Unknown error"}")
                }
            }
        }
    }

    // Receiving last location
    private suspend fun getLastLocation() {
        try {
            val lastLocation = fusedLocationClient.lastLocation
            if (lastLocation.isSuccessful && lastLocation.result != null) {
                val location = lastLocation.result
                val address = getAddressFromLocation(location.latitude, location.longitude)

                _locationState.update {
                    LocationState.Success(
                        address = address?.getAddressLine(0) ?: "Address not found",
                        latitude = location.latitude,
                        longitude = location.longitude,
                        fullAddress = address
                    )
                }
            } else {
                _locationState.update {
                    LocationState.Error("Couldn't get the location.\nCheck the GPS and the internet")
                }
            }
        } catch (e: SecurityException) {
            _locationState.update {
                LocationState.Error("Geolocation permission is missing")
            }
        }
    }

    // Check permission grant
    private fun hasLocationPermission(): Boolean {
        val context = getApplication<Application>()
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    // Receiving current location using getCurrentLocation
    @SuppressLint("MissingPermission")
    private suspend fun getFreshLocation(): Location =
        suspendCancellableCoroutine { continuation ->
            val cancellationTokenSource = CancellationTokenSource()

            // Подписываемся на отмену корутины
            continuation.invokeOnCancellation {
                cancellationTokenSource.cancel()
            }

            val task = fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationTokenSource.token
            )

            task.addOnSuccessListener { location ->
                if (location != null) {
                    continuation.resume(location)
                } else {
                    continuation.resumeWithException(Exception("Location is null"))
                }
            }

            task.addOnFailureListener { exception ->
                continuation.resumeWithException(exception)
            }
        }

    // Receiving address by longitude + latitude
    private suspend fun getAddressFromLocation(lat: Double, lng: Double): Address? {
        return try {
            // for Android 33+ used async method
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocation(lat, lng, 1) { addresses ->
                        if (addresses.isNotEmpty()) {
                            continuation.resume(addresses[0])
                        } else {
                            continuation.resume(null)
                        }
                    }
                }
            } else {
                // for old version of android using sync method
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lng, 1)
                addresses?.firstOrNull()
            }
        } catch (e: Exception) {
            // Geocoder cannot be work without internet or Google services
            null
        }
    }
}