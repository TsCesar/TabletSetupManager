#!/usr/bin/env bash
# Ejecuta las pruebas JUnit del núcleo puro (core sin APIs de Android) SIN Android SDK ni Gradle:
# solo con kotlinc + JDK 17+. Útil en equipos o entornos donde no se puede descargar el SDK.
# En CI se ejecutan las MISMAS pruebas con Gradle (:core:testDebugUnitTest).
#
# Variables (todas opcionales si los ficheros están en las rutas por defecto):
#   KOTLINC          ruta a kotlinc                    (por defecto: kotlinc del PATH)
#   GSON_JAR         gson-2.x.jar
#   APKSIG_JAR       jar con com.android.apksig (apksig-x.y.z.jar o bundletool-all-x.y.z.jar)
#   JUNIT_JAR        junit-4.13.2.jar
#   HAMCREST_JAR     hamcrest-core-1.3.jar
#   COROUTINES_JAR   kotlinx-coroutines-core-jvm.jar  (incluido en la distribución de kotlinc)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KOTLINC="${KOTLINC:-kotlinc}"
KLIB="$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")/../lib"
GSON_JAR="${GSON_JAR:?Define GSON_JAR}"
APKSIG_JAR="${APKSIG_JAR:?Define APKSIG_JAR}"
JUNIT_JAR="${JUNIT_JAR:?Define JUNIT_JAR}"
HAMCREST_JAR="${HAMCREST_JAR:?Define HAMCREST_JAR}"
COROUTINES_JAR="${COROUTINES_JAR:-$KLIB/kotlinx-coroutines-core-jvm.jar}"
OUT="${OUT:-$(mktemp -d)}"
mkdir -p "$OUT/main" "$OUT/test"

CORE="$ROOT/core/src/main/java/es/empresa/tabletsetup/core"
# Paquetes puros: todo salvo platform/ y ui/ (que dependen de Android).
mapfile -t MAIN_SRC < <(find "$CORE" -name '*.kt' -not -path '*/platform/*' -not -path '*/ui/*' | sort)
mapfile -t TEST_SRC < <(find "$ROOT/core/src/test/java" -name '*.kt' | sort)

# Garantía: el núcleo puro no importa APIs de Android.
if grep -l '^import android\.' "${MAIN_SRC[@]}"; then
  echo "ERROR: los archivos anteriores del núcleo puro importan APIs de Android" >&2
  exit 1
fi

CP="$GSON_JAR:$APKSIG_JAR:$COROUTINES_JAR"
echo "== Compilando núcleo puro (${#MAIN_SRC[@]} archivos)"
"$KOTLINC" -jvm-target 17 -nowarn -cp "$CP" -d "$OUT/main" "${MAIN_SRC[@]}"
echo "== Compilando pruebas (${#TEST_SRC[@]} archivos)"
"$KOTLINC" -jvm-target 17 -nowarn -cp "$CP:$JUNIT_JAR:$HAMCREST_JAR:$OUT/main" -d "$OUT/test" "${TEST_SRC[@]}"
cp -r "$ROOT/core/src/test/resources/." "$OUT/test/"

mapfile -t CLASSES < <(cd "$OUT/test" && find . -name '*Test.class' -not -name '*$*' | sed 's#^\./##; s#\.class$##; s#/#.#g' | sort)
echo "== Ejecutando ${#CLASSES[@]} clases de prueba"
java -cp "$OUT/test:$OUT/main:$CP:$JUNIT_JAR:$HAMCREST_JAR:$KLIB/kotlin-stdlib.jar" org.junit.runner.JUnitCore "${CLASSES[@]}"
