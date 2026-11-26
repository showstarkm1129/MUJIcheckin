package io.github.mujicheckin

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/** 学習済み店舗をジオフェンスとして Play 開発者サービスに登録する。 */
object GeofenceManager {
    private const val TAG = "MujiCheckin"
    const val RADIUS_M = 150f
    private const val MAX_FENCES = 100

    fun hasPermissions(context: Context): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, GeofenceReceiver::class.java),
            // ジオフェンスの PendingIntent は MUTABLE が必須
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

    @SuppressLint("MissingPermission")
    fun sync(context: Context) {
        val ctx = context.applicationContext
        val client = LocationServices.getGeofencingClient(ctx)
        val pi = pendingIntent(ctx)
        client.removeGeofences(pi).addOnCompleteListener {
            val stores = Prefs(ctx).stores.take(MAX_FENCES)
            if (stores.isEmpty() || !hasPermissions(ctx)) return@addOnCompleteListener
            val request = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofences(stores.map {
                    Geofence.Builder()
                        .setRequestId(it.id)
                        .setCircularRegion(it.lat, it.lng, RADIUS_M)
                        .setExpirationDuration(Geofence.NEVER_EXPIRE)
                        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                        .setNotificationResponsiveness(60_000)
                        .build()
                })
                .build()
            client.addGeofences(request, pi)
                .addOnSuccessListener { Log.i(TAG, "registered ${stores.size} geofences") }
                .addOnFailureListener { Log.w(TAG, "geofence registration failed", it) }
        }
    }
}
