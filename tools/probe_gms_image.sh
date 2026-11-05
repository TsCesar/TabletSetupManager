#!/usr/bin/env bash
# Sondeo de una imagen oficial de emulador con Google Play: apps de Google preinstaladas,
# versiones, instalador, estado y certificados de firma; prueba de apertura de Google Play.
set -u
OUT=${1:-out-gms}
mkdir -p "$OUT/apk"
export PATH="$ANDROID_HOME/build-tools/35.0.0:$PATH"
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 5; done
sleep 30
{ adb shell getprop ro.build.version.sdk; adb shell getprop ro.product.cpu.abilist; adb shell getprop ro.build.fingerprint; } > "$OUT/sistema.txt"
adb shell pm list packages -f > "$OUT/paquetes.txt"
adb shell pm list packages -d > "$OUT/paquetes-desactivados.txt"
adb shell pm list packages -u > "$OUT/paquetes-incl-desinstalados.txt"
for p in com.android.chrome com.google.android.gm com.google.android.apps.maps com.google.android.apps.mapslite com.waze com.whatsapp com.android.vending com.google.android.gms com.google.android.webview com.google.android.youtube; do
  {
    echo "== $p"
    adb shell dumpsys package "$p" | grep -E "versionCode|versionName|installerPackageName|initiatingPackageName|updateOwner|pkgFlags=|enabled=|codePath|primaryCpuAbi" | head -16
  } >> "$OUT/info.txt"
  paths=$(adb shell pm path "$p" | tr -d '\r' | sed 's/^package://')
  if [ -z "$paths" ]; then echo "   (no instalada)" >> "$OUT/info.txt"; continue; fi
  echo "   rutas: $(echo $paths)" >> "$OUT/info.txt"
  first=$(echo "$paths" | head -1)
  adb pull "$first" "$OUT/apk/$p.apk" >/dev/null 2>&1
  apksigner verify --print-certs -v "$OUT/apk/$p.apk" 2>&1 | grep -E "Verified using|Signer #1 certificate (DN|SHA-256|SHA-1)|Number of signers|lineage" | sed 's/^/   /' >> "$OUT/info.txt"
done
# Apertura de la ficha de Google Play (intent público market://), sin cuenta.
for p in com.waze com.android.chrome; do
  adb shell am start -W -a android.intent.action.VIEW -d "market://details?id=$p" -p com.android.vending > "$OUT/play-$p-am.txt" 2>&1
  sleep 15
  adb shell dumpsys activity activities | grep -E "ResumedActivity|topResumedActivity|mFocusedApp" | head -5 > "$OUT/play-$p-actividad.txt"
  adb exec-out screencap -p > "$OUT/play-$p.png"
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb exec-out cat /sdcard/ui.xml > "$OUT/play-$p-ui.xml"
  adb shell input keyevent KEYCODE_BACK; sleep 2; adb shell input keyevent KEYCODE_HOME; sleep 2
done
# ¿Se puede desactivar/activar Chrome y cómo se ve?
adb shell pm disable-user --user 0 com.android.chrome > "$OUT/chrome-disable.txt" 2>&1
adb shell dumpsys package com.android.chrome | grep -E "enabled=|User 0" | head -4 >> "$OUT/chrome-disable.txt"
adb shell pm enable com.android.chrome >> "$OUT/chrome-disable.txt" 2>&1
rm -rf "$OUT/apk"
echo FIN >> "$OUT/info.txt"
