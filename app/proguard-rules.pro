# R8 rules for the release build. The JNI bridge (CesiumLiveJniBridge's `external fun`s) needs
# nothing here: the default proguard-android-optimize.txt keeps native method names, and the
# Rust side never calls back into Java by name.

# JNA (engine/headless/CesiumHeadlessJnaBindings.kt) reflects on everything it touches: the
# Library interface becomes a runtime proxy, and Structure fields are looked up by the names in
# @FieldOrder. JNA's own classes are also looked up from its native dispatch code.
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.Structure { *; }
-keep interface * extends com.sun.jna.Library { *; }
-dontwarn java.awt.**

# Gson fills PexelsDestinationPhotoRepository's response classes by field name, so those names
# must match the Pexels JSON.
-keep class com.silas270.blocktime.data.repository.PexelsSearchResponse { <fields>; <init>(...); }
-keep class com.silas270.blocktime.data.repository.PexelsPhoto { <fields>; <init>(...); }
-keep class com.silas270.blocktime.data.repository.PexelsPhotoSrc { <fields>; <init>(...); }

# Same for the shared-challenge room shapes (data/network/room/RoomDto.kt), which Gson reads from
# the server and from the challenges.room_state cache by field name.
-keep class com.silas270.blocktime.data.network.room.ParticipantSnapshotDto { <fields>; <init>(...); }
-keep class com.silas270.blocktime.data.network.room.RoomDefinitionDto { <fields>; <init>(...); }
-keep class com.silas270.blocktime.data.network.room.OutcomeDto { <fields>; <init>(...); }
-keep class com.silas270.blocktime.data.network.room.RoomStateDto { <fields>; <init>(...); }
-keep class com.silas270.blocktime.data.network.room.RoomStateCacheDto { <fields>; <init>(...); }
-keep class com.silas270.blocktime.data.network.room.ClaimDto { <fields>; <init>(...); }

# Debug logging stays out of release builds. Log.i/w/e are kept.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# Readable crash stack traces in Play Console (the mapping file is uploaded with the bundle).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
