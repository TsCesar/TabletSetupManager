#!/usr/bin/env python3
"""Crea o actualiza el catálogo empresarial (schemaVersion 2) a partir de APK AUTORIZADAS.

Cada modificación incrementa "sequence" (las tablets rechazan catálogos más antiguos que el
último aplicado). Después hay que firmarlo con tools/sign_catalog.py.

Requiere Android SDK Build-Tools en el PATH (o ANDROID_HOME): aapt2 (o aapt) y apksigner.
NO descarga ni publica APK y NO concede derechos de redistribución: aloja solo binarios cuya
distribución esté autorizada.

Ejemplos:
  # Versión concreta alojada en vuestro servidor (APK base + splits opcionales)
  python tools/create_catalog.py add --catalog catalog.json --package com.waze \\
      --apk waze/base.apk --apk waze/split_config.arm64_v8a.apk \\
      --base-url https://descargas.empresa.example/waze/ --min-sdk 29

  # URL oficial del desarrollador (sin versión en el nombre) con certificado fijado
  python tools/create_catalog.py direct --catalog catalog.json --package com.whatsapp \\
      --url https://URL-OFICIAL-VERIFICADA/archivo.apk --signer <SHA-256 del certificado>

  # Fijar el certificado esperado para una fuente oficial integrada (p. ej. QuickSupport)
  python tools/create_catalog.py pin --catalog catalog.json \\
      --package com.teamviewer.quicksupport.market --signer <SHA-256>

Compatibilidad con la versión anterior: `create_catalog.py --package X --apk A --base-url U --out F`
equivale a `add --catalog F ...`.
"""
import argparse
import datetime
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

KNOWN_PACKAGES = {
    "com.artifex.mupdf.viewer.app", "com.teamviewer.quicksupport.market",
    "com.teamviewer.quicksupport.addon.universal", "com.android.chrome", "com.google.android.apps.maps",
    "com.waze", "com.whatsapp", "com.google.android.gm", "es.empresa.tabletsetup.updater",
}
HEX64 = re.compile(r"^[0-9a-fA-F]{64}$")


def tool(name):
    found = shutil.which(name)
    if found:
        return found
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if home:
        bt = Path(home, "build-tools")
        for version in sorted(bt.glob("*"), reverse=True):
            candidate = version / name
            if candidate.exists():
                return str(candidate)
    return None


def badging(apk: Path) -> str:
    for name in ("aapt2", "aapt"):
        exe = tool(name)
        if exe:
            return subprocess.check_output([exe, "dump", "badging", str(apk)], text=True, stderr=subprocess.STDOUT)
    sys.exit("Falta aapt2/aapt: instala Android SDK Build-Tools y añádelo al PATH")


def signer_of(apk: Path) -> str:
    exe = tool("apksigner")
    if not exe:
        sys.exit("Falta apksigner: instala Android SDK Build-Tools y añádelo al PATH")
    out = subprocess.check_output([exe, "verify", "--print-certs", str(apk)], text=True, stderr=subprocess.STDOUT)
    match = re.search(r"Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F:]{64,95})", out)
    if not match:
        sys.exit(f"No se pudo verificar la firma de {apk}")
    return match.group(1).replace(":", "").lower()


def load(path: Path) -> dict:
    if path.exists():
        doc = json.loads(path.read_text(encoding="utf-8"))
        if doc.get("schemaVersion") == 1:
            doc = {"schemaVersion": 2, "sequence": 0, "apps": doc.get("apps", [])}
        if doc.get("schemaVersion") != 2:
            sys.exit("Esquema de catálogo no compatible")
        return doc
    return {"schemaVersion": 2, "catalogId": "tablet-setup", "sequence": 0, "apps": []}


def save(path: Path, doc: dict):
    doc["sequence"] = int(doc.get("sequence", 0)) + 1
    doc["generatedAt"] = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    path.write_text(json.dumps(doc, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Catálogo guardado: {path} (secuencia {doc['sequence']}). Fírmalo ahora con tools/sign_catalog.py sign.")


def check_package(package: str):
    if "fuelsoft" in package.lower():
        sys.exit("FuelSoft está excluida de esta suite: tiene su propio procedimiento de instalación")
    if package not in KNOWN_PACKAGES:
        sys.exit(f"{package} no pertenece al catálogo de la suite (las tablets la ignorarían)")


def cmd_add(a):
    check_package(a.package)
    if not a.base_url.startswith("https://"):
        sys.exit("HTTPS obligatorio en --base-url")
    files = [Path(x).resolve() for x in a.apk]
    for f in files:
        if not f.is_file() or f.suffix.lower() != ".apk":
            sys.exit(f"Archivo no válido: {f}")
    info = badging(files[0])
    m = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", info, re.M)
    if not m:
        sys.exit("No se puede leer la identidad de la APK base")
    package, version_code, version_name = m.group(1), int(m.group(2)), m.group(3)
    if package != a.package:
        sys.exit(f"La APK es de {package}, no de {a.package}")
    if re.search(r"^package: .* split='", info, re.M):
        sys.exit("La primera APK debe ser la base (no un split)")
    sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", info, re.M)
    min_sdk = a.min_sdk or (int(sdk.group(1)) if sdk else 28)
    abis = set(re.findall(r"'([a-z0-9_-]+)'", (re.search(r"^native-code:(.*)$", info, re.M) or [None, ""])[1]))
    signer = signer_of(files[0])
    for split in files[1:]:
        si = badging(split)
        sm = re.search(r"^package: name='([^']+)' versionCode='(\d+)'", si, re.M)
        if not sm or sm.group(1) != package or int(sm.group(2)) != version_code:
            sys.exit(f"{split.name} no es un split del mismo paquete y versión")
        if signer_of(split) != signer:
            sys.exit(f"{split.name} está firmado con otro certificado")
        native = re.search(r"^native-code:(.*)$", si, re.M)
        if native:
            abis |= set(re.findall(r"'([a-z0-9_-]+)'", native.group(1)))
    assets = [{
        "url": a.base_url.rstrip("/") + "/" + f.name,
        "sha256": hashlib.sha256(f.read_bytes()).hexdigest(),
        "filename": f.name,
        "size": f.stat().st_size,
    } for f in files]
    entry = {
        "packageName": package, "type": "versioned", "versionCode": version_code, "versionName": version_name,
        "minSdk": min_sdk, "abis": sorted(a.abi or abis), "signingCertSha256": [signer],
        "override": bool(a.override), "assets": assets,
    }
    catalog = Path(a.catalog)
    doc = load(catalog)
    doc["apps"] = [x for x in doc.get("apps", []) if not (x.get("packageName") == package and x.get("versionCode") == version_code)]
    doc["apps"].append(entry)
    save(catalog, doc)
    print(f"Paquete {package} · versión {version_name} ({version_code}) · firma {signer}")
    print("Sube las APK a las URL indicadas; este script NO las publica.")


def cmd_direct(a):
    check_package(a.package)
    if not a.url.startswith("https://"):
        sys.exit("HTTPS obligatorio en --url")
    if a.signer and not HEX64.match(a.signer.replace(":", "")):
        sys.exit("--signer debe ser el SHA-256 (64 hex) del certificado")
    catalog = Path(a.catalog)
    doc = load(catalog)
    doc["apps"] = [x for x in doc.get("apps", []) if not (x.get("packageName") == a.package and x.get("type") == "direct")]
    entry = {"packageName": a.package, "type": "direct", "url": a.url, "override": bool(a.override)}
    if a.signer:
        entry["signingCertSha256"] = [a.signer.replace(":", "").lower()]
    doc["apps"].append(entry)
    save(catalog, doc)


def cmd_pin(a):
    check_package(a.package)
    value = a.signer.replace(":", "").lower()
    if not HEX64.match(value):
        sys.exit("--signer debe ser el SHA-256 (64 hex) del certificado")
    catalog = Path(a.catalog)
    doc = load(catalog)
    pins = doc.setdefault("signerPins", {})
    pins[a.package] = sorted(set(pins.get(a.package, [])) | {value})
    save(catalog, doc)


def main():
    argv = sys.argv[1:]
    if argv and argv[0].startswith("--"):  # sintaxis antigua
        argv = ["add"] + [("--catalog" if x == "--out" else x) for x in argv]
    p = argparse.ArgumentParser(description="Catálogo empresarial de APK autorizadas (schemaVersion 2)")
    sub = p.add_subparsers(dest="cmd", required=True)
    add = sub.add_parser("add", help="Añade una versión alojada en un servidor autorizado")
    add.add_argument("--catalog", default="catalog.json")
    add.add_argument("--package", required=True)
    add.add_argument("--apk", action="append", required=True, help="APK base primero; repetir para splits")
    add.add_argument("--base-url", required=True)
    add.add_argument("--min-sdk", type=int)
    add.add_argument("--abi", action="append")
    add.add_argument("--override", action="store_true", help="Prevalece sobre las fuentes oficiales integradas")
    d = sub.add_parser("direct", help="Declara una URL oficial directa")
    d.add_argument("--catalog", default="catalog.json")
    d.add_argument("--package", required=True)
    d.add_argument("--url", required=True)
    d.add_argument("--signer")
    d.add_argument("--override", action="store_true")
    pn = sub.add_parser("pin", help="Fija el certificado esperado de una app")
    pn.add_argument("--catalog", default="catalog.json")
    pn.add_argument("--package", required=True)
    pn.add_argument("--signer", required=True)
    a = p.parse_args(argv)
    {"add": cmd_add, "direct": cmd_direct, "pin": cmd_pin}[a.cmd](a)


if __name__ == "__main__":
    main()
