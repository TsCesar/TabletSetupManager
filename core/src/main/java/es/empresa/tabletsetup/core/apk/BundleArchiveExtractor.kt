package es.empresa.tabletsetup.core.apk

import es.empresa.tabletsetup.core.model.DeviceProfile
import es.empresa.tabletsetup.core.model.ValidationException
import es.empresa.tabletsetup.core.net.Downloader
import java.io.File
import java.util.zip.ZipFile

/** Abre APKM/APKS/XAPK/ZIP y extrae únicamente las APK compatibles. */
object BundleArchiveExtractor {
    /**
     * Importa APK normales y contenedores con splits.
     * Nunca ejecuta ni instala el ZIP directamente: extrae las piezas necesarias
     * con límites de cantidad/tamaño y el validador comprueba las APK después.
     */
    fun expand(selected: List<File>, dir: File, device: DeviceProfile): List<File> {
        if (selected.isEmpty()) throw ValidationException("No se ha seleccionado ningún archivo")
        if (selected.size > 40) throw ValidationException("Demasiados archivos seleccionados")
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw ValidationException("No se pudo crear la carpeta temporal para extraer las APK")
        }
        val result = mutableListOf<File>()
        var extractedBytes = 0L

        selected.forEachIndexed { archiveIndex, file ->
            if (!file.isFile || file.length() == 0L) {
                throw ValidationException("Archivo vacío o inexistente: " + file.name)
            }
            val name = file.name.lowercase(java.util.Locale.ROOT)
            when {
                name.endsWith(".apk") -> result += file
                name.endsWith(".apkm") || name.endsWith(".apks") ||
                    name.endsWith(".xapk") || name.endsWith(".zip") -> {
                    try {
                        ZipFile(file).use { zip ->
                            val entries = zip.entries().asSequence().toList()
                            if (entries.size > 512) throw ValidationException("Demasiadas entradas en el paquete")
                            entries.forEach { entry ->
                                val entryName = entry.name
                                if (entryName.startsWith("/") || entryName.contains('\\') ||
                                    entryName.split('/').any { it == ".." } ||
                                    entryName.any { it.code < 32 }) {
                                    throw ValidationException("El contenedor contiene rutas peligrosas")
                                }
                                if (!entry.isDirectory && entryName.lowercase(java.util.Locale.ROOT).endsWith(".obb")) {
                                    throw ValidationException("El archivo incluye datos OBB que aún no se pueden instalar")
                                }
                            }
                            val entriesApk = entries.filter {
                                !it.isDirectory && it.name.lowercase(java.util.Locale.ROOT).endsWith(".apk")
                            }
                            val selectedNames = BundleSplitSelector.select(entriesApk.map { it.name }, device).toSet()
                            entriesApk.filter { it.name in selectedNames }.forEachIndexed { index, entry ->
                                if (result.size >= 40) throw ValidationException("El paquete requiere más de 40 APK")
                                if (entry.size > Downloader.DEFAULT_MAX_APK_BYTES) {
                                    throw ValidationException("Una APK del contenedor es demasiado grande")
                                }
                                // Nombre de destino controlado: no se reutilizan rutas del ZIP.
                                val output = File(dir, "archive_" + archiveIndex + "_" + index + ".apk")
                                var partBytes = 0L
                                zip.getInputStream(entry).use { input ->
                                    output.outputStream().use { out ->
                                        val buffer = ByteArray(128 * 1024)
                                        while (true) {
                                            val amount = input.read(buffer)
                                            if (amount < 0) break
                                            partBytes += amount
                                            extractedBytes += amount
                                            if (partBytes > Downloader.DEFAULT_MAX_APK_BYTES ||
                                                extractedBytes > 800L * 1024 * 1024) {
                                                throw ValidationException("El contenedor supera el tamaño máximo permitido")
                                            }
                                            out.write(buffer, 0, amount)
                                        }
                                    }
                                }
                                if (partBytes == 0L) throw ValidationException("El contenedor tiene una APK vacía")
                                result += output
                            }
                        }
                    } catch (e: ValidationException) {
                        throw e
                    } catch (e: java.util.zip.ZipException) {
                        throw ValidationException("El contenedor APKM/APKS/XAPK está corrupto")
                    }
                }
                else -> throw ValidationException(
                    "Archivo no admitido: " + file.name + " (usa APK, APKM, APKS, XAPK o ZIP)"
                )
            }
        }
        if (result.isEmpty()) throw ValidationException("No se encontraron APK en el paquete")
        return result
    }
}
