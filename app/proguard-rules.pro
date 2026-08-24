# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Mapsforge
-keep class org.mapsforge.** { *; }
-dontwarn org.mapsforge.**

# Play Services Location
-keep class com.google.android.gms.location.** { *; }
