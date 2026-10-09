package com.newoether.agora.tool

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Locale

/**
 * Agent location tools, ported from AIOPE's `get_location` / `search_location`.
 *
 * `get_location` returns the device's current GPS coordinates and a reverse-geocoded place name.
 * `search_location` geocodes a free-text place/address query (using the platform Geocoder) and
 * returns candidate matches. Both respect the user's location permission and require it to be
 * granted before returning data.
 */
class LocationToolProvider(private val context: Context) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.locationEnabled) return emptyList()
        return listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = "get_location",
                    description = "Get the device's current GPS coordinates and reverse-geocoded " +
                        "place name. Requires the location permission. Returns latitude, longitude, " +
                        "accuracy, and a human-readable address when available.",
                    parameters = ToolParameters(properties = emptyMap()),
                ),
            ),
            ToolDefinition(
                function = ToolFunction(
                    name = "search_location",
                    description = "Search for a place, address, landmark, or business by free text " +
                        "and return candidate matches with coordinates. Requires the location " +
                        "permission. Call get_location first for nearby searches.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "query" to ToolProperty(
                                "string",
                                "The place, address, or landmark to search for.",
                            ),
                            "limit" to ToolProperty(
                                "integer",
                                "Maximum number of results (1-10, default 5).",
                            ),
                        ),
                        required = listOf("query"),
                    ),
                ),
            ),
        )
    }

    override fun handles(name: String): Boolean = name in TOOL_NAMES

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String {
        if (!ctx.locationEnabled) return error("Location tools are disabled")
        return when (name) {
            "get_location" -> executeGetLocation()
            "search_location" -> executeSearchLocation(arguments)
            else -> error("Unknown location tool: $name")
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(): Location? = withContext(Dispatchers.IO) {
        if (!hasLocationPermission()) return@withContext null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        var best: Location? = null
        for (provider in providers) {
            runCatching { lm.getLastKnownLocation(provider) }
                .getOrNull()
                ?.let { loc ->
                    if (best == null || loc.accuracy < best!!.accuracy) best = loc
                }
        }
        best
    }

    private suspend fun executeGetLocation(): String {
        val location = currentLocation()
            ?: return error("Location unavailable. Grant the location permission and try again.")
        val address = reverseGeocode(location.latitude, location.longitude)
        return buildJsonObject {
            put("type", "get_location")
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("accuracy_m", location.accuracy)
            put("provider", location.provider)
            address?.let { put("address", it) }
        }.toString()
    }

    private suspend fun executeSearchLocation(arguments: String): String {
        val args = runCatching {
            Json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject
        }.getOrNull() ?: return error("Arguments are not a JSON object.")
        val query = (args["query"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: return error("query is required")
        val limit = ((args["limit"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 5).coerceIn(1, 10)
        if (!hasLocationPermission()) {
            return error("Location permission is required to search for places.")
        }
        val results = withContext(Dispatchers.IO) {
            runCatching {
                Geocoder(context, Locale.getDefault())
                    .getFromLocationName(query, limit)
                    .orEmpty()
            }.getOrDefault(emptyList())
        }
        if (results.isEmpty()) {
            return buildJsonObject {
                put("type", "search_location")
                put("query", query)
                put("error", "no_results")
            }.toString()
        }
        return buildJsonObject {
            put("type", "search_location")
            put("query", query)
            putJsonArray("results") {
                results.forEach { a ->
                    add(
                        buildJsonObject {
                            put("name", a.featureName ?: "")
                            put("address", a.getAddressLine(0) ?: "")
                            put("latitude", a.latitude)
                            put("longitude", a.longitude)
                        },
                    )
                }
            }
        }.toString()
    }

    private suspend fun reverseGeocode(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        runCatching {
            Geocoder(context, Locale.getDefault())
                .getFromLocation(lat, lon, 1)
                ?.firstOrNull()
                ?.getAddressLine(0)
        }.getOrNull()
    }

    private fun error(message: String): String = "Error: $message"

    private companion object {
        const val GET_LOCATION = "get_location"
        const val SEARCH_LOCATION = "search_location"
        val TOOL_NAMES = setOf(GET_LOCATION, SEARCH_LOCATION)
    }
}