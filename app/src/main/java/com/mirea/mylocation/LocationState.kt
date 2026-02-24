package com.mirea.mylocation

import android.location.Address

sealed class LocationState {
    object Initial : LocationState()
    object Loading : LocationState()
    data class Success(
        val address: String,
        val latitude: Double,
        val longitude: Double,
        val fullAddress: Address? = null
    ) : LocationState()
    data class Error(val message: String) : LocationState()
}