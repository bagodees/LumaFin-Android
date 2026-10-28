# Jellyfin SDK models are kotlinx.serialization classes; keep their generated serializers
-keepclassmembers class org.jellyfin.sdk.model.** { *** Companion; }
-dontwarn org.slf4j.**
