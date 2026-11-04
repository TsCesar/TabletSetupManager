#!/usr/bin/env python3
"""Comprobaciones estáticas del proyecto (rápidas, sin SDK). No sustituyen a la compilación
Android ni a las pruebas en tablet; complementan a las pruebas JUnit y a tools/verify_apks.py."""
import json
import re
import sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parents[1])
core = root / "core/src/main/java/es/empresa/tabletsetup/core"
fails = []


def check(cond, msg):
    print(("OK    " if cond else "FALLO ") + msg)
    if not cond:
        fails.append(msg)


catalog = (core / "catalog/AppCatalog.kt").read_text(encoding="utf-8")
packages = set(re.findall(r'const val [A-Z_]+ = "([a-z0-9_.]+)"', catalog))
check(not re.search(r'(packageName|label)\s*=\s*"[^"]*fuelsoft', catalog, re.I), "FuelSoft no aparece en el catálogo de la suite")
for pkg in ["com.artifex.mupdf.viewer.app", "com.teamviewer.quicksupport.market", "com.teamviewer.quicksupport.addon.universal",
            "com.android.chrome", "com.google.android.apps.maps", "com.waze", "com.whatsapp", "com.google.android.gm",
            "es.empresa.tabletsetup.updater"]:
    check(pkg in packages, f"Catálogo incluye {pkg}")
policy = (core / "policy/CompatibilityPolicy.kt").read_text(encoding="utf-8")
check("WAZE_MIN_SDK = 29" in policy and 'MSG_WAZE_ANDROID9 = "No compatible con Android 9"' in policy, "Regla de Waze: Android 10+ y mensaje exacto")
check("fuelsoft" in policy, "Lista de exclusión de FuelSoft presente")
fdroid = (core / "fdroid/FdroidIndex.kt").read_text(encoding="utf-8")
check("index-v1.jar" in fdroid and "PINNED_INDEX_SIGNER_SHA256" in fdroid, "F-Droid: índice firmado con certificado fijado")
check('"packages" ->' in fdroid and "reader.beginObject()" in fdroid, "F-Droid: 'packages' se lee como objeto (formato real)")
validator = (core / "apk/ApkSetValidator.kt").read_text(encoding="utf-8")
for fragment in ["expectedPackage", "expectedVersionCode", "minSdk", "abiCompatible", "expectedFileHashes", "pinnedSigners",
                 "installed.signers", "versionRule", "splitName"]:
    check(fragment in validator, f"Validación previa a instalar: {fragment}")
installation = (core / "platform/Installation.kt").read_text(encoding="utf-8")
check("MODE_FULL_INSTALL" in installation and "USER_ACTION_REQUIRED" in installation, "PackageInstaller con confirmación del sistema")
check("STATUS_PENDING_USER_ACTION" in installation, "Gestión de la confirmación pendiente")
downloader = (core / "net/Downloader.kt").read_text(encoding="utf-8")
check('equals("https"' in downloader and "Range" in downloader, "Descargas solo HTTPS con reanudación")
catalog_parser = (core / "catalog/EnterpriseCatalog.kt").read_text(encoding="utf-8")
check("SHA256withECDSA" in catalog_parser and "sequence" in catalog_parser, "Catálogo empresarial firmado y con secuencia")
pure = [p for p in core.rglob("*.kt") if "/platform/" not in str(p) and "/ui/" not in str(p)]
check(all("import android." not in p.read_text(encoding="utf-8") for p in pure), "Núcleo puro sin APIs de Android (probado en JVM)")
worker = (root / "updater/src/main/java/es/empresa/tabletsetup/updater/UpdateWorker.kt").read_text(encoding="utf-8")
check("PeriodicWorkRequestBuilder" in worker and "ExistingPeriodicWorkPolicy.KEEP" in worker, "Updater: WorkManager periódico")
settings = (core / "platform/AndroidPlatform.kt").read_text(encoding="utf-8")
check("DEFAULT_INTERVAL_HOURS = 24" in settings, "Intervalo inicial de 24 horas")
updater_build = (root / "updater/build.gradle.kts").read_text(encoding="utf-8")
check('project(":installer")' not in updater_build, "Updater no depende del módulo Installer")
manifests = {m: (root / m / "src/main/AndroidManifest.xml").read_text(encoding="utf-8") for m in ["core", "installer", "updater"]}
check(all("permission.QUERY_ALL_PACKAGES\"" not in t for t in manifests.values()), "Sin QUERY_ALL_PACKAGES")
check("REQUEST_DELETE_PACKAGES" in manifests["installer"] and "REQUEST_DELETE_PACKAGES" not in manifests["updater"], "Solo el Installer puede pedir su desinstalación")
check(all("fuelsoft" not in t.lower() for t in manifests.values()), "FuelSoft ausente de los manifiestos")
for f in ["gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties", ".github/workflows/android.yml"]:
    check((root / f).exists(), f"Existe {f}")
props = (root / "gradle/wrapper/gradle-wrapper.properties").read_text()
check("gradle-8.14.3-bin.zip" in props, "Wrapper de Gradle 8.14.3")
example = json.loads((root / "docs/catalogo-empresarial.example.json").read_text(encoding="utf-8"))
check(example.get("schemaVersion") == 2 and isinstance(example.get("sequence"), int), "Catálogo de ejemplo con esquema 2")
check(not any("fuelsoft" in a.get("packageName", "").lower() for a in example.get("apps", [])), "Catálogo de ejemplo sin FuelSoft")
gp = (root / "gradle.properties").read_text()
check("tsm.release" not in gp and "storePassword" not in gp, "Sin contraseñas de firma release en el repositorio")

print(f"\n{'TODO CORRECTO' if not fails else str(len(fails)) + ' FALLO(S)'}")
sys.exit(1 if fails else 0)
