package com.jarvis.assistant.automation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.util.CommandResult
import com.jarvis.assistant.util.LunaLogger

class NavigationService(private val context: Context) {

    companion object {
        private const val TAG = "NavigationService"
        private const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"

        @Volatile private var instance: NavigationService? = null
        fun getInstance(context: Context): NavigationService {
            return instance ?: synchronized(this) {
                instance ?: NavigationService(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs by lazy { (context.applicationContext as JarvisApp).preferences }

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * Gets device's real-time GPS / network location. Never hardcodes coordinates.
     */
    fun getCurrentLocation(): Location? {
        if (!hasLocationPermission()) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        return try {
            val gpsLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val netLoc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val passiveLoc = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)

            listOfNotNull(gpsLoc, netLoc, passiveLoc).maxByOrNull { it.time }
        } catch (e: SecurityException) {
            LunaLogger.e(TAG, "SecurityException getting location: ${e.message}")
            null
        }
    }

    /**
     * Launches Google Maps navigation from the device's current GPS location to the destination.
     */
    fun startNavigation(destinationQuery: String): CommandResult {
        val cleanDest = destinationQuery.trim()
        if (cleanDest.isBlank()) {
            return CommandResult(false, "Destination specify nahi kiya gaya.")
        }

        // Check and resolve Home / Office aliases
        val resolvedDestination = when (cleanDest.lowercase()) {
            "home", "ghar" -> {
                val saved = prefs.homeAddress
                if (saved.isNotBlank()) saved else "Home"
            }
            "office", "work", "kaam" -> {
                val saved = prefs.officeAddress
                if (saved.isNotBlank()) saved else "Work"
            }
            else -> cleanDest
        }

        // 1. Preferred Official URI: google.navigation:q=<destination>&mode=d
        val encodedDest = Uri.encode(resolvedDestination)
        val navUri = Uri.parse("google.navigation:q=$encodedDest&mode=d")
        val mapIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            setPackage(GOOGLE_MAPS_PACKAGE)
        }

        val packageManager = context.packageManager
        val canHandleMaps = mapIntent.resolveActivity(packageManager) != null

        if (canHandleMaps) {
            val launched = BackgroundActivityLauncher.launch(context, mapIntent)
            return if (launched) {
                CommandResult(true, "$resolvedDestination ke liye Google Maps navigation shuru kar diya.")
            } else {
                CommandResult(false, "Google Maps launch nahi ho saka.")
            }
        }

        // 2. Fallback: Universal Web Directions URL
        val webFallbackUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$encodedDest")
        val webIntent = Intent(Intent.ACTION_VIEW, webFallbackUri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        val webLaunched = BackgroundActivityLauncher.launch(context, webIntent)
        return if (webLaunched) {
            CommandResult(true, "$resolvedDestination ke liye route open kar diya hai.")
        } else {
            CommandResult(false, "Navigation app ya browser open nahi ho saka.")
        }
    }
}
