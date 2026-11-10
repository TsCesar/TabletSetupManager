# Reglas propias del Updater (las comunes están en core/consumer-rules.pro).
# WorkManager aporta sus propias reglas de consumo; el worker se instancia por reflexión.
-keep class es.empresa.tabletsetup.updater.UpdateWorker { <init>(...); }
