#!/usr/bin/env bash
# Pruebas que no necesitan Android SDK: comprobaciones estáticas + JUnit del núcleo con kotlinc.
# Ver tests/run_local_tests.sh para las variables necesarias (GSON_JAR, APKSIG_JAR, JUNIT_JAR, HAMCREST_JAR).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
python3 "$HERE/check_project.py" "$HERE/.."
bash "$HERE/run_local_tests.sh"
