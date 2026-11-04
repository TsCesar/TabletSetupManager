#!/usr/bin/env python3
"""Comprobaciones automáticas de las APK generadas (sin tablet física).

Usa las herramientas OFICIALES de Android:
  - aapt2 (Build-Tools, o el incluido en bundletool-all.jar) para leer manifiestos;
  - apksigner (Build-Tools) o, en su defecto, apksig mediante tools/ApkSigCheck.java.

  python3 tools/verify_apks.py --installer dist/TabletSetupInstaller-debug.apk \\
      --updater dist/TabletUpdater-debug.apk --report dist/VERIFICACION_APK.md

Sale con código 1 si alguna comprobación falla.
"""
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

INSTALLER = "es.empresa.tabletsetup.installer"
UPDATER = "es.empresa.tabletsetup.updater"
COMMON_PERMS = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.POST_NOTIFICATIONS",
}
ALLOWED = {
    INSTALLER: COMMON_PERMS | {
        "android.permission.REQUEST_DELETE_PACKAGES",
        "es.empresa.tabletsetup.permission.CONFIGURE_UPDATER",
        f"{INSTALLER}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    },
    UPDATER: COMMON_PERMS | {
        # Requeridos por WorkManager (reprogramación tras reinicio y ejecución del trabajo).
        "android.permission.WAKE_LOCK",
        "android.permission.RECEIVE_BOOT_COMPLETED",
        "android.permission.FOREGROUND_SERVICE",
        f"{UPDATER}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    },
}
FORBIDDEN = {"android.permission.QUERY_ALL_PACKAGES", "android.permission.READ_EXTERNAL_STORAGE",
             "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.MANAGE_EXTERNAL_STORAGE",
             "android.permission.READ_CONTACTS", "android.permission.ACCESS_FINE_LOCATION",
             "android.permission.CAMERA", "android.permission.RECORD_AUDIO", "android.permission.INSTALL_PACKAGES"}


class Report:
    def __init__(self):
        self.rows = []

    def check(self, name, ok, detail=""):
        self.rows.append((name, bool(ok), detail))
        print(("OK   " if ok else "FALLO") + f"  {name}" + (f" — {detail}" if detail else ""))
        return ok

    @property
    def ok(self):
        return all(r[1] for r in self.rows)

    def markdown(self, header):
        lines = [header, "", "| Comprobación | Resultado | Detalle |", "|---|---|---|"]
        for name, ok, detail in self.rows:
            lines.append(f"| {name} | {'✅ OK' if ok else '❌ FALLO'} | {detail.replace('|', '/')} |")
        return "\n".join(lines) + "\n"


def find_tool(name, explicit=None):
    if explicit:
        return explicit
    found = shutil.which(name)
    if found:
        return found
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if home:
        for version in sorted(Path(home, "build-tools").glob("*"), reverse=True):
            if (version / name).exists():
                return str(version / name)
    return None


def badging(aapt2, apk):
    out = subprocess.run([aapt2, "dump", "badging", str(apk)], capture_output=True, text=True)
    return out.stdout + out.stderr


def parse_badging(text):
    info = {}
    m = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", text, re.M)
    if m:
        info["package"], info["versionCode"], info["versionName"] = m.group(1), int(m.group(2)), m.group(3)
    m = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", text, re.M)
    info["minSdk"] = int(m.group(1)) if m else None
    m = re.search(r"^targetSdkVersion:'(\d+)'", text, re.M)
    info["targetSdk"] = int(m.group(1)) if m else None
    info["permissions"] = set(re.findall(r"^uses-permission: name='([^']+)'", text, re.M))
    m = re.search(r"^launchable-activity: name='([^']+)'", text, re.M)
    info["launcher"] = m.group(1) if m else None
    m = re.search(r"^application-label:'([^']*)'", text, re.M)
    info["label"] = m.group(1) if m else None
    m = re.search(r"^native-code:(.*)$", text, re.M)
    info["native"] = m.group(1).strip() if m else ""
    return info


def signature(apk, apksigner, apksig_jar, tools_dir):
    if apksigner:
        out = subprocess.run([apksigner, "verify", "--min-sdk-version", "28", "--print-certs", "-v", str(apk)],
                             capture_output=True, text=True)
        text = out.stdout + out.stderr
        signers = [s.replace(":", "").lower() for s in re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)", text)]
        schemes = re.findall(r"Verified using (v\d(?:\.\d)?) scheme \(APK Signature Scheme v\d(?:\.\d)?\): true", text)
        return out.returncode == 0, signers, ",".join(schemes)
    if apksig_jar:
        out = subprocess.run(["java", "-cp", apksig_jar, str(Path(tools_dir, "ApkSigCheck.java")), str(apk)],
                             capture_output=True, text=True)
        text = out.stdout
        signers = re.findall(r"Signer SHA-256: ([0-9a-f]{64})", text)
        schemes = re.search(r"Schemes: (.*)", text)
        return "Verified: true" in text, signers, schemes.group(1) if schemes else ""
    return None, [], "sin apksigner ni apksig"


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--installer", required=True)
    p.add_argument("--updater", required=True)
    p.add_argument("--report")
    p.add_argument("--aapt2")
    p.add_argument("--apksigner")
    p.add_argument("--apksig-jar", help="jar con apksig (bundletool-all.jar) si no hay apksigner")
    a = p.parse_args()
    tools_dir = Path(__file__).resolve().parent
    aapt2 = find_tool("aapt2", a.aapt2)
    apksigner = find_tool("apksigner", a.apksigner) if not a.apksig_jar else None
    r = Report()
    apks = {INSTALLER: Path(a.installer), UPDATER: Path(a.updater)}
    signers = {}
    infos = {}
    for pkg, apk in apks.items():
        short = "Installer" if pkg == INSTALLER else "Updater"
        if not r.check(f"{short}: archivo existe", apk.is_file(), str(apk)):
            continue
        with open(apk, "rb") as fh:
            magic = fh.read(4)
        r.check(f"{short}: es un ZIP/APK real (no renombrado)", magic == b"PK\x03\x04", f"{apk.stat().st_size:,} bytes".replace(",", "."))
        with zipfile.ZipFile(apk) as z:
            names = set(z.namelist())
            bad = z.testzip()
        r.check(f"{short}: integridad ZIP (CRC)", bad is None, bad or "todas las entradas correctas")
        r.check(f"{short}: contiene AndroidManifest.xml y classes.dex", {"AndroidManifest.xml", "classes.dex"} <= names)
        r.check(f"{short}: sin código nativo (válida para cualquier arquitectura)", not any(n.startswith("lib/") for n in names))
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        r.check(f"{short}: SHA-256", True, digest)
        if aapt2:
            info = parse_badging(badging(aapt2, apk))
            infos[pkg] = info
            r.check(f"{short}: packageName", info.get("package") == pkg, str(info.get("package")))
            r.check(f"{short}: versión", info.get("versionCode") is not None, f"{info.get('versionName')} ({info.get('versionCode')})")
            r.check(f"{short}: minSdk 28 (Android 9)", info.get("minSdk") == 28, str(info.get("minSdk")))
            r.check(f"{short}: targetSdk", (info.get("targetSdk") or 0) >= 34, str(info.get("targetSdk")))
            r.check(f"{short}: actividad de inicio", info.get("launcher") is not None, str(info.get("launcher")))
            perms = info["permissions"]
            extra = perms - ALLOWED[pkg]
            r.check(f"{short}: sin permisos innecesarios", not extra and not (perms & FORBIDDEN),
                    ", ".join(sorted(p.split(".")[-1] for p in perms)) + (f" · NO PERMITIDOS: {sorted(extra)}" if extra else ""))
            manifest = subprocess.run([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk)],
                                      capture_output=True, text=True).stdout
            r.check(f"{short}: FuelSoft ausente del manifiesto", "fuelsoft" not in manifest.lower())
            r.check(f"{short}: sin tráfico HTTP en claro", 'usesCleartextTraffic' in manifest and "=false" in manifest.split("usesCleartextTraffic")[1][:40].replace('"', ''),
                    "usesCleartextTraffic=false")
        ok, sig, schemes = signature(apk, apksigner, a.apksig_jar, tools_dir)
        signers[pkg] = sig
        if ok is not None:
            r.check(f"{short}: firma APK válida (Android 9+)", ok and bool(sig), f"esquemas {schemes}; certificado {sig[0] if sig else '-'}")

    with zipfile.ZipFile(apks[INSTALLER]) as z:
        embedded = "assets/embedded/TabletUpdater.apk" in z.namelist()
        r.check("Installer: incluye Tablet Updater embebido", embedded, "assets/embedded/TabletUpdater.apk")
        if embedded:
            with tempfile.TemporaryDirectory() as tmp:
                path = Path(z.extract("assets/embedded/TabletUpdater.apk", tmp))
                same = hashlib.sha256(path.read_bytes()).hexdigest() == hashlib.sha256(apks[UPDATER].read_bytes()).hexdigest()
                r.check("Updater embebido idéntico al TabletUpdater.apk entregado", same)
                if aapt2:
                    r.check("Updater embebido: packageName", parse_badging(badging(aapt2, path)).get("package") == UPDATER)
    with zipfile.ZipFile(apks[UPDATER]) as z:
        r.check("Updater independiente (no contiene ni requiere el Installer)",
                not any(n.startswith("assets/embedded/") for n in z.namelist()) and
                (INSTALLER not in str(infos.get(UPDATER, {}).get("permissions", ""))))
    if signers.get(INSTALLER) and signers.get(UPDATER):
        r.check("Installer y Updater firmados con la misma clave", signers[INSTALLER] == signers[UPDATER], signers[INSTALLER][0])

    if a.report:
        tools = f"aapt2: {aapt2 or 'no disponible'} · firma: {'apksigner ' + apksigner if apksigner else ('apksig ' + a.apksig_jar if a.apksig_jar else 'no verificada')}"
        Path(a.report).write_text(r.markdown("# Verificación automática de las APK\n\n" + tools), encoding="utf-8")
    print("\nRESULTADO:", "TODO CORRECTO" if r.ok else "HAY FALLOS")
    sys.exit(0 if r.ok else 1)


if __name__ == "__main__":
    main()
