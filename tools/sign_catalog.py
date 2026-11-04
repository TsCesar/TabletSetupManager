#!/usr/bin/env python3
"""Firma y verificación del catálogo empresarial (ECDSA P-256 / SHA-256).

Las aplicaciones solo aceptan el catálogo si `<url>.sig` contiene una firma válida
con la clave pública configurada (BuildConfig o ajustes de la tablet).

Requisitos: Python 3.8+ y el paquete `cryptography` (pip install cryptography).

  python tools/sign_catalog.py keygen  --out claves/catalog-signing-key.pem
  python tools/sign_catalog.py sign    --key claves/catalog-signing-key.pem catalog.json
  python tools/sign_catalog.py verify  --public-key claves/catalog-public-key.txt catalog.json
  python tools/sign_catalog.py config  --url https://servidor/catalog.json \\
                                       --public-key claves/catalog-public-key.txt --out tablet-catalog-config.json

La CLAVE PRIVADA no debe subirse nunca al repositorio ni al servidor de descargas.
Alternativa sin Python (OpenSSL):
  openssl ecparam -name prime256v1 -genkey -noout -out key.pem
  openssl ec -in key.pem -pubout -outform DER | base64 -w0      # clave pública Base64
  openssl dgst -sha256 -sign key.pem catalog.json | base64 -w0 > catalog.json.sig
"""
import argparse
import base64
import json
import os
import sys
from pathlib import Path

try:
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
except ImportError:  # pragma: no cover
    sys.exit("Falta el paquete 'cryptography': pip install cryptography")


def load_public_key(value: str):
    path = Path(value)
    text = path.read_text().strip() if path.is_file() else value.strip()
    if "BEGIN PUBLIC KEY" in text:
        return serialization.load_pem_public_key(text.encode())
    return serialization.load_der_public_key(base64.b64decode("".join(text.split())))


def public_b64(public_key) -> str:
    der = public_key.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return base64.b64encode(der).decode()


def cmd_keygen(a):
    out = Path(a.out)
    if out.exists() and not a.force:
        sys.exit(f"{out} ya existe (usa --force para sobrescribir)")
    out.parent.mkdir(parents=True, exist_ok=True)
    key = ec.generate_private_key(ec.SECP256R1())
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    out.write_bytes(pem)
    os.chmod(out, 0o600)
    pub = public_b64(key.public_key())
    pub_file = out.with_name("catalog-public-key.txt")
    pub_file.write_text(pub + "\n")
    print(f"Clave privada (SECRETA, no la subas a ningún repositorio): {out}")
    print(f"Clave pública guardada en: {pub_file}")
    print(f"Clave pública (tsm.catalog.publicKey):\n{pub}")


def cmd_sign(a):
    key = serialization.load_pem_private_key(Path(a.key).read_bytes(), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey) or key.curve.name != "secp256r1":
        sys.exit("La clave debe ser ECDSA P-256 (secp256r1)")
    catalog = Path(a.catalog)
    data = catalog.read_bytes()
    doc = json.loads(data)
    if doc.get("schemaVersion") != 2 or not isinstance(doc.get("sequence"), int):
        sys.exit("El catálogo debe tener schemaVersion 2 y un número 'sequence' (genéralo con create_catalog.py)")
    signature = key.sign(data, ec.ECDSA(hashes.SHA256()))
    sig_path = Path(a.out) if a.out else catalog.with_name(catalog.name + ".sig")
    sig_path.write_text(base64.b64encode(signature).decode() + "\n")
    print(f"Firma escrita en {sig_path} (secuencia {doc['sequence']}). Publica ambos archivos en la misma URL.")


def cmd_verify(a):
    catalog = Path(a.catalog)
    data = catalog.read_bytes()
    sig_path = Path(a.sig) if a.sig else catalog.with_name(catalog.name + ".sig")
    raw = sig_path.read_bytes()
    signature = raw if raw[:1] == b"\x30" and len(raw) > 8 and raw[2:3] == b"\x02" else base64.b64decode(b"".join(raw.split()))
    try:
        load_public_key(a.public_key).verify(signature, data, ec.ECDSA(hashes.SHA256()))
    except InvalidSignature:
        sys.exit("FIRMA NO VÁLIDA: las tablets rechazarán este catálogo")
    doc = json.loads(data)
    print(f"Firma válida. Secuencia {doc.get('sequence')}, {len(doc.get('apps', []))} entradas.")


def cmd_config(a):
    pub = public_b64(load_public_key(a.public_key))
    if not a.url.startswith("https://"):
        sys.exit("La URL debe ser HTTPS")
    Path(a.out).write_text(json.dumps({"catalogUrl": a.url, "publicKey": pub}, indent=2) + "\n")
    print(f"Archivo de configuración para importar en las tablets: {a.out}")


def main():
    p = argparse.ArgumentParser(description="Firma del catálogo empresarial de Tablet Setup Manager")
    sub = p.add_subparsers(dest="cmd", required=True)
    k = sub.add_parser("keygen", help="Genera un par de claves ECDSA P-256")
    k.add_argument("--out", default="catalog-signing-key.pem")
    k.add_argument("--force", action="store_true")
    s = sub.add_parser("sign", help="Firma catalog.json -> catalog.json.sig")
    s.add_argument("--key", required=True)
    s.add_argument("--out")
    s.add_argument("catalog")
    v = sub.add_parser("verify", help="Verifica la firma como lo harán las tablets")
    v.add_argument("--public-key", required=True, help="Base64, PEM o archivo")
    v.add_argument("--sig")
    v.add_argument("catalog")
    c = sub.add_parser("config", help="Crea el archivo de configuración importable en la app")
    c.add_argument("--url", required=True)
    c.add_argument("--public-key", required=True)
    c.add_argument("--out", default="tablet-catalog-config.json")
    a = p.parse_args()
    {"keygen": cmd_keygen, "sign": cmd_sign, "verify": cmd_verify, "config": cmd_config}[a.cmd](a)


if __name__ == "__main__":
    main()
