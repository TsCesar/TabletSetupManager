#!/usr/bin/env python3
"""Prueba de extremo a extremo en un emulador Android (se ejecuta en GitHub Actions).

Instala las APK debug, ejecuta el flujo real del Installer (F-Droid, descarga oficial de
TeamViewer, Updater embebido) pulsando las confirmaciones del sistema, y comprueba:
regla de Waze, FuelSoft ausente, instalación por lotes, registro de WorkManager,
comportamiento sin Internet, independencia del Updater y ausencia de cierres inesperados.

  python3 tools/emulator_e2e.py --api 28 --installer dist/TabletSetupInstaller-debug.apk --out e2e-api28

Las pruebas de terceros (descargas reales de F-Droid/TeamViewer) dependen de la red del
servidor de CI; su resultado se registra igualmente.
"""
import argparse
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

INSTALLER = "es.empresa.tabletsetup.installer"
UPDATER = "es.empresa.tabletsetup.updater"
MUPDF = "com.artifex.mupdf.viewer.app"
QS = "com.teamviewer.quicksupport.market"
WAZE = "com.waze"
TAG = "TabletSuite"
INSTALLER_PKGS = ("com.android.packageinstaller", "com.google.android.packageinstaller")
CONFIRM_TEXTS = {"install", "instalar", "update", "actualizar"}
DISMISS_TEXTS = {"wait", "esperar", "ok", "aceptar", "got it", "install anyway", "instalar de todos modos", "don't send", "no enviar"}


def run(args, timeout=180, check=False):
    p = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    if check and p.returncode != 0:
        raise RuntimeError(f"{' '.join(args)} -> {p.returncode}: {p.stdout} {p.stderr}")
    return p.stdout + p.stderr


def adb(*args, timeout=180, check=False):
    return run(["adb", *args], timeout=timeout, check=check)


def sh(cmd, timeout=180):
    return adb("shell", cmd, timeout=timeout)


class Results:
    def __init__(self, api):
        self.api = api
        self.rows = []

    def add(self, name, ok, detail="", critical=True):
        self.rows.append({"prueba": name, "ok": bool(ok), "detalle": detail, "critica": critical})
        print(("OK   " if ok else ("FALLO" if critical else "AVISO")) + f"  {name} — {detail}", flush=True)

    @property
    def ok(self):
        return all(r["ok"] for r in self.rows if r["critica"])

    def markdown(self):
        lines = [f"# Prueba en emulador Android API {self.api}", "",
                 "| Prueba | Resultado | Detalle |", "|---|---|---|"]
        for r in self.rows:
            res = "✅ OK" if r["ok"] else ("❌ FALLO" if r["critica"] else "⚠️ Aviso")
            lines.append(f"| {r['prueba']} | {res} | {r['detalle'].replace('|', '/')[:300]} |")
        return "\n".join(lines) + "\n"


def logcat():
    return adb("logcat", "-d", "-v", "brief", f"{TAG}:I", "*:S")


def wait_log(pattern, timeout, after=0):
    """Espera a que aparezca un patrón en el logcat de la app; devuelve el texto completo."""
    end = time.time() + timeout
    regex = re.compile(pattern)
    while time.time() < end:
        text = logcat()
        if len(regex.findall(text)) > after:
            return text
        time.sleep(3)
    return None


def states(text):
    """Último estado de cada app: {paquete: (ESTADO, mensaje)}."""
    found = {}
    for m in re.finditer(r"estado (\S+) (\S+) \| (.*)", text):
        found[m.group(1)] = (m.group(2), m.group(3).strip())
    return found


def screenshot(out, name):
    path = Path(out, name)
    with open(path, "wb") as fh:
        subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=fh, timeout=60)
    return path


def ui_nodes():
    out = sh("uiautomator dump /sdcard/ui.xml", timeout=60)
    if "dumped" not in out.lower():
        return []
    xml = adb("exec-out", "cat", "/sdcard/ui.xml", timeout=60)
    try:
        root = ET.fromstring(xml[xml.find("<?xml"):] if "<?xml" in xml else xml)
    except ET.ParseError:
        return []
    return list(root.iter("node"))


def tap(node):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if not m:
        return False
    x1, y1, x2, y2 = map(int, m.groups())
    sh(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
    return True


def handle_system_dialogs(out, shots):
    """Pulsa la confirmación del instalador del sistema u otros diálogos. Devuelve True si pulsó «Instalar»."""
    for node in ui_nodes():
        text = (node.get("text") or "").strip().lower()
        pkg = node.get("package") or ""
        if pkg in INSTALLER_PKGS and text in CONFIRM_TEXTS and node.get("enabled") == "true":
            if not shots.get("confirm"):
                shots["confirm"] = str(screenshot(out, "02-confirmacion-android.png"))
            return tap(node)
        if text in DISMISS_TEXTS and pkg not in (INSTALLER, UPDATER) and node.get("clickable") == "true":
            tap(node)
            return False
    return False


def wait_boot():
    adb("wait-for-device", timeout=600)
    end = time.time() + 600
    while time.time() < end:
        if sh("getprop sys.boot_completed").strip() == "1":
            return
        time.sleep(5)
    raise RuntimeError("El emulador no terminó de arrancar")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", type=int, required=True)
    ap.add_argument("--installer", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    r = Results(a.api)
    shots = {}
    try:
        wait_boot()
        sdk = sh("getprop ro.build.version.sdk").strip()
        abis = sh("getprop ro.product.cpu.abilist").strip()
        r.add("Emulador arrancado", sdk == str(a.api), f"API {sdk} · ABI {abis}")
        for setting in ("package_verifier_enable 0", "verifier_verify_adb_installs 0"):
            sh(f"settings put global {setting}")

        # 1. Instalación del Installer (como haría el técnico con adb o el gestor de archivos).
        res = adb("install", "-r", "-g", a.installer, timeout=300)
        r.add("Instalar Tablet Setup Installer", "Success" in res, res.strip().splitlines()[-1] if res.strip() else "")
        sh(f"appops set {INSTALLER} REQUEST_INSTALL_PACKAGES allow")
        adb("logcat", "-c")
        sh(f"am start -W -n {INSTALLER}/.MainActivity")
        text = wait_log(r"comprobacion terminada modo=INSTALLER", 240)
        r.add("Installer: comprobación de versiones completada", text is not None)
        st = states(text or "")
        screenshot(out, "01-installer-inicio.png")
        r.add("Installer: estados de las 9 aplicaciones", len(st) == 9, ", ".join(f"{k.split('.')[-1]}={v[0]}" for k, v in st.items()))
        r.add("FuelSoft no aparece", not any("fuelsoft" in k.lower() for k in st))
        waze = st.get(WAZE, ("?", ""))
        if a.api == 28:
            r.add("Android 9: Waze bloqueado con «No compatible con Android 9»", waze[0] == "OMITIDA" and waze[1] == "No compatible con Android 9", f"{waze[0]} · {waze[1]}")
        else:
            r.add(f"Android API {a.api}: Waze no se omite (queda sujeto a su versión/fuente)", waze[0] != "OMITIDA", f"{waze[0]} · {waze[1]}")
        mupdf = st.get(MUPDF, ("?", ""))
        r.add("MuPDF disponible desde F-Droid (índice firmado real)", mupdf[0] in ("NO_INSTALADA", "AL_DIA", "ACTUALIZACION") and "F-Droid" in mupdf[1],
              f"{mupdf[0]} · {mupdf[1]}", critical=False)
        qs = st.get(QS, ("?", ""))
        r.add("QuickSupport disponible (descarga oficial TeamViewer)", qs[0] in ("NO_INSTALADA", "POR_VERIFICAR", "AL_DIA"), f"{qs[0]} · {qs[1]}", critical=False)
        upd = st.get(UPDATER, ("?", ""))
        r.add("Tablet Updater disponible (embebido)", upd[0] == "NO_INSTALADA" and "Installer" in upd[1], f"{upd[0]} · {upd[1]}")

        # 2. Instalación por lotes con confirmaciones de Android.
        before = logcat().count("lote terminado")
        sh(f"am start -n {INSTALLER}/.MainActivity --es es.empresa.tabletsetup.extra.START_ACTION instalar_todo")
        confirmations = 0
        end = time.time() + 1200
        finished = None
        while time.time() < end:
            if handle_system_dialogs(out, shots):
                confirmations += 1
                time.sleep(4)
            text = logcat()
            if text.count("lote terminado") > before:
                finished = text
                break
            time.sleep(3)
        r.add("Lote «Instalar todas las compatibles» terminado", finished is not None, f"{confirmations} confirmación(es) de Android pulsadas")
        results = {m.group(1): (m.group(2) == "true", m.group(4)) for m in re.finditer(r"resultado (\S+) ok=(\w+) omitida=(\w+) \| (.*)", finished or "")}
        for pkg, (ok, msg) in results.items():
            r.add(f"Resultado del lote: {pkg}", ok, msg, critical=pkg == UPDATER)
        time.sleep(5)
        screenshot(out, "03-installer-tras-lote.png")
        packages = sh("pm list packages")
        r.add("Tablet Updater instalado por el Installer (sesión PackageInstaller)", f"package:{UPDATER}" in packages)
        r.add("MuPDF instalado", f"package:{MUPDF}" in packages, critical=False)
        r.add("QuickSupport instalado", f"package:{QS}" in packages, critical=False)
        for pkg in (MUPDF, QS, UPDATER):
            info = sh(f"dumpsys package {pkg} | grep -E 'versionCode|installerPackageName' | head -3")
            if info.strip():
                (out / f"paquete-{pkg}.txt").write_text(info)

        # 3. Tablet Updater: comprobación y registro de la tarea periódica.
        adb("logcat", "-c")
        sh(f"am start -W -n {UPDATER}/.MainActivity")
        text = wait_log(r"comprobacion terminada modo=UPDATER", 240)
        r.add("Updater: comprobación manual completada", text is not None)
        ust = states(text or "")
        not_installed = [k for k, v in ust.items() if "solo actualiza aplicaciones ya instaladas" in v[1]]
        r.add("Updater: no ofrece instalar apps no instaladas", len(not_installed) > 0, ", ".join(x.split(".")[-1] for x in not_installed))
        time.sleep(3)
        screenshot(out, "04-updater.png")
        jobs = sh("dumpsys jobscheduler", timeout=120)
        r.add("Updater: tarea periódica registrada en JobScheduler (WorkManager)", UPDATER in jobs and "SystemJobService" in jobs)
        block = "\n".join(l for l in jobs.splitlines() if UPDATER in l)[:2000]
        (out / "jobscheduler-updater.txt").write_text(block)

        # 4. Sin Internet.
        sh("svc wifi disable")
        sh("svc data disable")
        time.sleep(8)
        adb("logcat", "-c")
        sh(f"am force-stop {UPDATER}")
        sh(f"am start -W -n {UPDATER}/.MainActivity")
        text = wait_log(r"comprobacion terminada modo=UPDATER", 180)
        offline = text is not None and "online=false" in text
        ost = states(text or "")
        qs_off = ost.get(QS, ("?", ""))
        r.add("Sin Internet: se detecta y se informa sin bloquear la app", offline, f"QuickSupport: {qs_off[0]} · {qs_off[1]}")
        time.sleep(2)
        screenshot(out, "05-updater-sin-internet.png")
        sh("svc wifi enable")
        sh("svc data enable")
        time.sleep(8)

        # 5. El Updater funciona sin el Installer.
        uninstall = adb("uninstall", INSTALLER)
        adb("logcat", "-c")
        sh(f"am force-stop {UPDATER}")
        sh(f"am start -W -n {UPDATER}/.MainActivity")
        text = wait_log(r"comprobacion terminada modo=UPDATER", 240)
        r.add("Updater funciona con el Installer desinstalado", "Success" in uninstall and text is not None, uninstall.strip())
        jobs = sh("dumpsys jobscheduler", timeout=120)
        r.add("Tarea periódica sigue registrada sin el Installer", UPDATER in jobs)

        # 6. Cierres inesperados.
        crashes = adb("logcat", "-d", "-b", "crash")
        ours = [l for l in crashes.splitlines() if "es.empresa.tabletsetup" in l]
        r.add("Sin cierres inesperados (logcat crash)", not ours, "\n".join(ours[:5]))
    except Exception as e:  # noqa: BLE001
        r.add("Ejecución de la prueba", False, f"{type(e).__name__}: {e}")
    finally:
        (out / "logcat-tabletsuite.txt").write_text(logcat())
        (out / "resultados.json").write_text(json.dumps(r.rows, ensure_ascii=False, indent=2))
        (out / f"RESULTADOS_EMULADOR_API{a.api}.md").write_text(r.markdown())
    sys.exit(0 if r.ok else 1)


if __name__ == "__main__":
    main()
