# NanoHTTPD reflects on nothing, but R8 must keep the websocket entry points
# that are only referenced from the server thread.
-keep class org.nanohttpd.** { *; }
-dontwarn org.nanohttpd.**

# kotlinx.serialization generates serializers that R8 cannot see are used.
-keepclassmembers class com.rpgmaps.tabletop.display.protocol.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.rpgmaps.tabletop.display.protocol.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# The Cast framework instantiates the options provider by name from the manifest.
-keep class com.rpgmaps.tabletop.display.cast.CastOptionsProvider { *; }
