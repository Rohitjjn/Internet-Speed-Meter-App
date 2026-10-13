# ProGuard / R8 rules for Internet Speed Meter (release build).
#
# Compose, Room, DataStore, Coroutines and WorkManager all ship their own
# consumer rules, so only the app's reflection-sensitive pieces need attention.

# Room entities + DAOs (KSP generates impls; keep the public surface).
-keep class com.example.data.** { *; }

# The speed counter reader is referenced only from the service (kept), but the
# /proc/net/dev path strings must survive.
-keep class com.example.services.TrafficCounterReader { *; }

# WorkManager workers are discovered by class name.
-keep class com.example.services.SpeedKeepAliveWorker { *; }

# Keep the data usage entity field names in case of any reflection.
-keepclassmembers class com.example.data.DataUsageEntity {
    <fields>;
}
