#!/usr/bin/env python3
"""Prueba de extremo a extremo en un emulador Android (se ejecuta en GitHub Actions).

Instala las APK debug, ejecuta el flujo real del Installer (F-Droid, descarga oficial de
TeamViewer, Updater embebido) pulsando las confirmaciones del sistema, y comprueba:
regla de Waze, FuelSoft ausente, instalación por lotes, activación del Updater desde el
Installer (con el permiso de notificaciones de Android 13+), registro de WorkManager,
comportamiento sin Internet (Wi-Fi, datos y modo avión), retirada del Installer con su botón
final, independencia del Updater y ausencia de cierres inesperados.

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
PERMISSION_PKGS = ("com.android.permissioncontroller", "com.google.android.permissioncontroller")
CONFIRM_TEXTS = {"install", "instalar", "update", "actualizar"}
ALLOW_TEXTS = {"allow", "permitir"}
DISMISS_TEXTS = {"wait", "esperar", "ok", "aceptar", "got it", "install anyway", "instalar de todos modos", "don't send", "no enviar"}
LOG_CHUNKS = []  # logcat de la app acumulado entre limpiezas (para el informe)


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


def clear_log(label):
    """Guarda el logcat de la app hasta ahora y lo vacía para la siguiente fase."""
    LOG_CHUNKS.append(f"===== {label} =====\n{logcat()}")
    adb("logcat", "-c")


def start_activity(package):
    """Abre la actividad principal en una tarea nueva y limpia. Sin --activity-clear-task, si quedó
    un diálogo de permisos del sistema en la tarea de la app, Android solo trae esa tarea al frente
    y la actividad no se vuelve a crear."""
    return sh(f"am start -W --activity-clear-task -n {package}/.MainActivity")


def wait_log(pattern, timeout, after=0, on_poll=None):
    """Espera a que aparezca un patrón en el logcat de la app; devuelve el texto completo."""
    end = time.time() + timeout
    regex = re.compile(pattern)
    while time.time() < end:
        if on_poll:
            on_poll()
        text = logcat()
        if len(regex.findall(text)) > after:
            return text
        time.sleep(3)
    return None


def active_network():
    """Red por defecto según ConnectivityService: un identificador, «none» o «?» si no se sabe."""
    m = re.search(r"Active default network: (\S+)", sh("dumpsys connectivity", timeout=60))
    return m.group(1) if m else "?"


def set_network(enabled, api):
    """Corta o restablece toda la conectividad del emulador (Wi-Fi, datos y, en API 30+, modo avión)."""
    if enabled:
        if api >= 30:
            sh("cmd connectivity airplane-mode disable")
        sh("svc wifi enable")
        sh("svc data enable")
    else:
        if api >= 30:
            sh("cmd connectivity airplane-mode enable")
        sh("svc wifi disable")
        sh("svc data disable")


def internet_validated():
    """(¿la red por defecto tiene Internet validado?, id de la red)."""
    text = sh("dumpsys connectivity", timeout=60)
    m = re.search(r"Active default network: (\d+)", text)
    if not m:
        return False, "none"
    nid = m.group(1)
    return any(f"network{{{nid}}}" in line and "VALIDATED" in line for line in text.splitlines()), nid


def wait_internet(timeout):
    """Espera a que el emulador tenga Internet de verdad (el arranque deja la red sin conectar un rato)."""
    end = time.time() + timeout
    nid = "none"
    while time.time() < end:
        ok, nid = internet_validated()
        if ok:
            return True, nid
        time.sleep(5)
    return False, nid


def wait_network(online, timeout):
    """Espera a que haya (o deje de haber) red por defecto. Devuelve (conseguido, estado)."""
    end = time.time() + timeout
    state = "?"
    while time.time() < end:
        state = active_network()
        if state == "?":
            time.sleep(10)  # este Android no informa: espera fija
            return None, state
        if (state != "none") == online:
            return True, state
        time.sleep(3)
    return False, state


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
    """Nodos de la pantalla. Cada nodo lleva «_enabled»: el estado del elemento pulsable que lo
    contiene (en Compose el texto de un botón es un nodo hijo que siempre figura como habilitado)."""
    out = sh("uiautomator dump /sdcard/ui.xml", timeout=60)
    if "dumped" not in out.lower():
        return []
    xml = adb("exec-out", "cat", "/sdcard/ui.xml", timeout=60)
    try:
        root = ET.fromstring(xml[xml.find("<?xml"):] if "<?xml" in xml else xml)
    except ET.ParseError:
        return []
    parents = {child: parent for parent in root.iter() for child in parent}
    nodes = list(root.iter("node"))
    for node in nodes:
        holder = node
        while holder is not None and holder.get("clickable") != "true" and holder.get("class") != "android.widget.Button":
            holder = parents.get(holder)
        node.set("_enabled", (holder if holder is not None else node).get("enabled", "false"))
    return nodes


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


def allow_permission_dialog():
    """Pulsa «Permitir» en el diálogo de permisos de Android (notificaciones, Android 13+)."""
    for node in ui_nodes():
        text = (node.get("text") or "").strip().lower()
        rid = node.get("resource-id") or ""
        if node.get("package") in PERMISSION_PKGS and (text in ALLOW_TEXTS or rid.endswith("permission_allow_button")):
            return tap(node)
    return False


def notifications_granted(package):
    return "POST_NOTIFICATIONS: granted=true" in sh(f"dumpsys package {package} | grep POST_NOTIFICATIONS")


def tap_button(packages, texts):
    """Pulsa un botón habilitado de los paquetes indicados cuyo texto (en minúsculas) esté en [texts]."""
    for node in ui_nodes():
        text = (node.get("text") or "").strip().lower()
        if node.get("package") in packages and text in texts and node.get("enabled") == "true":
            return tap(node)
    return False


def find_node(text, swipes=12):
    """Busca un elemento por su texto exacto, desplazando la lista hacia abajo si hace falta."""
    m = re.search(r"(\d+)x(\d+)", sh("wm size"))
    w, h = (int(m.group(1)), int(m.group(2))) if m else (1080, 1920)
    for i in range(swipes + 1):
        for node in ui_nodes():
            if (node.get("text") or "").strip() == text:
                return node
        if i < swipes:
            sh(f"input swipe {w // 2} {int(h * 0.75)} {w // 2} {int(h * 0.3)} 400")
            time.sleep(1.5)
    return None


def updater_job_registered(timeout=30):
    end = time.time() + timeout
    while time.time() < end:
        if UPDATER in sh("dumpsys jobscheduler", timeout=120):
            return True
        time.sleep(3)
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
        started = time.time()
        net_ok, net_id = wait_internet(300)
        r.add("Emulador con Internet antes de empezar (las descargas de F-Droid y TeamViewer son reales)", net_ok,
              f"red {net_id} validada tras {int(time.time() - started)} s" if net_ok else f"sin Internet validado tras 300 s (red {net_id})",
              critical=False)

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
        r.add("Installer: estados de las 11 aplicaciones", len(st) == 11, ", ".join(f"{k.split('.')[-1]}={v[0]}" for k, v in st.items()))
        r.add("FuelSoft no aparece", not any("fuelsoft" in k.lower() for k in st))
        waze = st.get(WAZE, ("?", ""))
        if a.api == 28:
            r.add("Android 9: Waze bloqueado con «No compatible con Android 9»", waze[0] == "OMITIDA" and waze[1] == "No compatible con Android 9", f"{waze[0]} · {waze[1]}")
        else:
            r.add(f"Android API {a.api}: Waze no se omite (queda sujeto a su versión/fuente)", waze[0] != "OMITIDA", f"{waze[0]} · {waze[1]}")
        mupdf = st.get(MUPDF, ("?", ""))
        mupdf_expected = mupdf[0] != "NO_COMPATIBLE"
        if mupdf_expected:
            r.add("MuPDF disponible desde F-Droid (índice firmado real)", mupdf[0] in ("NO_INSTALADA", "AL_DIA", "ACTUALIZACION") and "F-Droid" in mupdf[1],
                  f"{mupdf[0]} · {mupdf[1]}", critical=False)
        else:
            # Emuladores solo x86_64 (API 30+): F-Droid puede no publicar esa arquitectura. Debe verse «No compatible».
            r.add("MuPDF sin APK de F-Droid para la arquitectura del emulador: «No compatible», sin bloquear el lote",
                  "arquitectura" in mupdf[1], f"{mupdf[0]} · {mupdf[1]}", critical=False)
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
        if mupdf_expected:
            r.add("MuPDF instalado", f"package:{MUPDF}" in packages, critical=False)
        else:
            r.add("MuPDF no compatible: no se intentó instalar", f"package:{MUPDF}" not in packages and MUPDF not in results)
        r.add("QuickSupport instalado", f"package:{QS}" in packages, critical=False)
        for pkg in (MUPDF, QS, UPDATER):
            info = sh(f"dumpsys package {pkg} | grep -E 'versionCode|installerPackageName|primaryCpuAbi' | head -4")
            if info.strip():
                (out / f"paquete-{pkg}.txt").write_text(info)

        # 2b. Activar el Updater desde el Installer, como el técnico. Antes de activarlo, el
        #     Installer no debe permitir su propia desinstalación.
        button = find_node("Desinstalar Tablet Setup Installer")
        if button is None:
            r.add("Installer: no permite desinstalarse antes de activar el Updater", False, "botón no encontrado en la interfaz", critical=False)
        else:
            # Encontrado: si estuviera habilitado sería un fallo real del requisito.
            r.add("Installer: no permite desinstalarse antes de activar el Updater", button.get("_enabled") == "false",
                  "botón deshabilitado" if button.get("_enabled") == "false" else "botón habilitado")
        screenshot(out, "03a-installer-antes-de-activar.png")
        if "dumped" in sh("uiautomator dump /sdcard/ui.xml", timeout=60).lower():
            (out / "ui-antes-de-activar.xml").write_text(adb("exec-out", "cat", "/sdcard/ui.xml", timeout=60))
        activate = find_node("Activar y configurar Updater")
        notif_at_activation = False
        if activate is None:
            r.add("Installer → «Activar y configurar Updater»", False, "botón no encontrado en la interfaz", critical=False)
        else:
            tap(activate)
            time.sleep(4)
            if a.api >= 33:
                for _ in range(8):
                    if allow_permission_dialog():
                        break
                    time.sleep(2)
                time.sleep(2)
                notif_at_activation = notifications_granted(UPDATER)
            r.add("Installer → «Activar y configurar Updater» (actividad protegida por permiso de firma) registra la comprobación periódica",
                  updater_job_registered(), "tarea del Updater en JobScheduler sin haber abierto el Updater")
            if a.api >= 33:
                r.add("Activación: el Updater pide el permiso de notificaciones (Android 13+) y queda concedido", notif_at_activation,
                      "«Permitir» pulsado en el diálogo del sistema", critical=False)
            time.sleep(2)
            button = find_node("Desinstalar Tablet Setup Installer", swipes=3)
            r.add("Installer: permite desinstalarse con el Updater activado", button is not None and button.get("_enabled") == "true",
                  "botón habilitado" if button is not None and button.get("_enabled") == "true" else "botón deshabilitado o no encontrado")
        screenshot(out, "03b-installer-updater-activado.png")

        # 3. Tablet Updater: comprobación y registro de la tarea periódica.
        clear_log("Installer: comprobación e instalación por lotes")
        start_activity(UPDATER)
        perm = {"tapped": False}

        def allow_notifications():
            # Android 13+: el Updater (instalado por el Installer, sin -g) pide el permiso de notificaciones.
            if a.api >= 33 and not perm["tapped"]:
                perm["tapped"] = allow_permission_dialog()

        text = wait_log(r"comprobacion terminada modo=UPDATER", 240, on_poll=allow_notifications)
        r.add("Updater: comprobación manual completada", text is not None)
        ust = states(text or "")
        not_installed = [k for k, v in ust.items() if "solo actualiza aplicaciones ya instaladas" in v[1]]
        r.add("Updater: no ofrece instalar apps no instaladas", len(not_installed) > 0, ", ".join(x.split(".")[-1] for x in not_installed))
        if a.api >= 33 and not notif_at_activation:
            for _ in range(6):
                if perm["tapped"] or notifications_granted(UPDATER):
                    break
                allow_notifications()
                time.sleep(2)
            granted = notifications_granted(UPDATER)
            r.add("Updater: pide el permiso de notificaciones al abrirse (Android 13+) y queda concedido", granted,
                  "«Permitir» pulsado en el diálogo del sistema" if perm["tapped"] else "no apareció el diálogo", critical=False)
            if not granted:
                sh(f"pm grant {UPDATER} android.permission.POST_NOTIFICATIONS")
        time.sleep(3)
        screenshot(out, "04-updater.png")
        jobs = sh("dumpsys jobscheduler", timeout=120)
        r.add("Updater: tarea periódica registrada en JobScheduler (WorkManager)", UPDATER in jobs and "SystemJobService" in jobs)
        block = "\n".join(l for l in jobs.splitlines() if UPDATER in l)[:2000]
        (out / "jobscheduler-updater.txt").write_text(block)

        # 4. Sin Internet (Wi-Fi y datos apagados; en API 30+ además modo avión).
        net_before = active_network()
        set_network(False, a.api)
        _, net_off = wait_network(False, 60)
        clear_log("Updater: comprobación con red")
        sh(f"am force-stop {UPDATER}")
        start_activity(UPDATER)
        text = wait_log(r"comprobacion terminada modo=UPDATER", 240)
        offline = text is not None and "online=false" in text
        ost = states(text or "")
        qs_off = ost.get(QS, ("?", ""))
        r.add("Sin Internet: se detecta y se informa sin bloquear la app", offline,
              f"red por defecto {net_before} → {net_off} · QuickSupport: {qs_off[0]} · {qs_off[1]}")
        time.sleep(2)
        screenshot(out, "05-updater-sin-internet.png")
        set_network(True, a.api)
        back, net_back = wait_internet(180)
        r.add("Red restablecida después de la prueba sin Internet", back, f"red por defecto: {net_back}", critical=False)

        # 5. Retirar el Installer con su botón final (como el técnico) y usar el Updater sin él.
        clear_log("Updater: sin Internet")
        start_activity(INSTALLER)
        wait_log(r"comprobacion terminada modo=INSTALLER", 240)
        time.sleep(2)
        removed_by_ui = False
        button = find_node("Desinstalar Tablet Setup Installer")
        if button is not None and button.get("_enabled") == "true" and tap(button):
            time.sleep(2)
            confirm = find_node("Solicitar desinstalación", swipes=0)
            if confirm is not None and tap(confirm):
                for _ in range(10):  # diálogo de Android «¿Desinstalar esta aplicación?»
                    time.sleep(2)
                    if tap_button(INSTALLER_PKGS, {"ok", "aceptar", "desinstalar", "uninstall"}):
                        break
                for _ in range(15):
                    if f"package:{INSTALLER}" not in sh("pm list packages"):
                        removed_by_ui = True
                        break
                    time.sleep(2)
        r.add("Installer retirado desde su botón final (con confirmación de Android)", removed_by_ui,
              "desinstalado" if removed_by_ui else "no se completó por la interfaz: se usa adb uninstall", critical=False)
        uninstall = "Success" if removed_by_ui else adb("uninstall", INSTALLER)
        clear_log("Installer: desinstalación")
        sh(f"am force-stop {UPDATER}")
        start_activity(UPDATER)
        text = wait_log(r"comprobacion terminada modo=UPDATER", 300)
        online = re.search(r"comprobacion terminada modo=UPDATER online=(\w+)", text or "")
        r.add("Updater funciona con el Installer desinstalado", "Success" in uninstall and text is not None,
              f"desinstalación: {uninstall.strip()} · comprobación: " + (f"terminada (online={online.group(1)})" if online else "no terminó"))
        r.add("Installer desinstalado", f"package:{INSTALLER}" not in sh("pm list packages"))
        jobs = sh("dumpsys jobscheduler", timeout=120)
        r.add("Tarea periódica sigue registrada sin el Installer", UPDATER in jobs)

        # 6. Cierres inesperados.
        crashes = adb("logcat", "-d", "-b", "crash")
        ours = [l for l in crashes.splitlines() if "es.empresa.tabletsetup" in l]
        r.add("Sin cierres inesperados (logcat crash)", not ours, "\n".join(ours[:5]))
    except Exception as e:  # noqa: BLE001
        r.add("Ejecución de la prueba", False, f"{type(e).__name__}: {e}")
    finally:
        try:
            LOG_CHUNKS.append(f"===== Final =====\n{logcat()}")
            (out / "logcat-completo-ultima-fase.txt").write_text(adb("logcat", "-d", "-v", "time", "-t", "4000", timeout=120))
        except Exception as e:  # noqa: BLE001
            LOG_CHUNKS.append(f"(no se pudo leer logcat: {e})")
        (out / "logcat-tabletsuite.txt").write_text("\n".join(LOG_CHUNKS))
        (out / "resultados.json").write_text(json.dumps(r.rows, ensure_ascii=False, indent=2))
        (out / f"RESULTADOS_EMULADOR_API{a.api}.md").write_text(r.markdown())
    sys.exit(0 if r.ok else 1)


if __name__ == "__main__":
    main()
