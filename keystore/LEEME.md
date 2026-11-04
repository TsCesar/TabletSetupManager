# keystore/debug.keystore — SOLO PARA PRUEBAS

* Keystore de **depuración** compartido (PKCS12, alias `androiddebugkey`, contraseña `android`).
* Certificado: `CN=TabletSetupManager DEBUG - SOLO PRUEBAS, O=Pruebas internas, C=ES`
  (SHA-256 `C4:C8:8E:B8:55:42:15:E3:0B:76:6A:E8:1D:3A:53:D6:00:C9:4F:E4:92:43:3D:BF:4E:06:0C:DB:2C:7F:E9:11`).
* Es público a propósito: así las APK debug de cualquier compilación (local o GitHub Actions) tienen la misma
  firma y se pueden actualizar unas sobre otras en las tablets de prueba.
* **No usar en producción.** Las versiones release se firman con vuestra clave privada (ver README, sección 7);
  una tablet con APK debug debe desinstalarlas antes de instalar las release.
