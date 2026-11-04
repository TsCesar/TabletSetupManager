# Reglas que se aplican a Installer y Updater cuando se minifica la versión release.

# apksig (verificación de firmas APK v1/v2/v3 en el propio dispositivo).
-keep class com.android.apksig.** { *; }
-dontwarn com.android.apksig.**

# Gson solo se usa en modo árbol/streaming (JsonParser, JsonReader): no requiere
# reglas de reflexión. Se mantiene el receptor de resultados de PackageInstaller,
# que se declara en el manifiesto.
-keep class es.empresa.tabletsetup.core.platform.InstallStatusReceiver { *; }
