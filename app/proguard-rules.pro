# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.**

# DataStore
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
