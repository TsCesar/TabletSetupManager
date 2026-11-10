package es.empresa.tabletsetup.core.apk

import es.empresa.tabletsetup.core.model.ValidationException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lector mínimo del XML binario de Android (AXML) usado en AndroidManifest.xml.
 * Permite leer el manifiesto de APK base y APK divididas (split) sin depender de
 * PackageManager, cuyo comportamiento con splits varía según el fabricante.
 */
class BinaryXmlParser(data: ByteArray) {
    data class Attribute(
        val namespace: String?,
        val name: String,
        val resourceId: Int,
        val type: Int,
        val data: Int,
        val stringValue: String?,
    )

    data class Element(val name: String, val depth: Int, val attributes: List<Attribute>) {
        fun attr(resourceId: Int, name: String): Attribute? =
            attributes.firstOrNull { it.resourceId == resourceId } ?: attributes.firstOrNull { it.name == name }

        fun plainAttr(name: String): Attribute? = attributes.firstOrNull { it.name == name && it.namespace.isNullOrEmpty() }
    }

    private val buffer: ByteBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    private var strings: List<String> = emptyList()
    private var resourceIds: IntArray = IntArray(0)

    fun parse(maxElements: Int = 20_000): List<Element> {
        if (buffer.limit() < 8) throw ValidationException("Manifiesto binario vacío o truncado")
        val type = u16(0)
        val headerSize = u16(2)
        if (type != RES_XML_TYPE || headerSize < 8) throw ValidationException("AndroidManifest.xml no es XML binario de Android")
        val elements = mutableListOf<Element>()
        var depth = 0
        var offset = headerSize
        val end = minOf(u32(4).toLong(), buffer.limit().toLong()).toInt()
        while (offset + 8 <= end) {
            val chunkType = u16(offset)
            val chunkHeader = u16(offset + 2)
            val chunkSize = u32(offset + 4)
            if (chunkSize < 8 || offset.toLong() + chunkSize > end) throw ValidationException("Manifiesto binario corrupto")
            when (chunkType) {
                RES_STRING_POOL_TYPE -> strings = readStringPool(offset, chunkHeader, chunkSize)
                RES_XML_RESOURCE_MAP_TYPE -> {
                    val count = (chunkSize - chunkHeader) / 4
                    resourceIds = IntArray(count) { i -> buffer.getInt(offset + chunkHeader + i * 4) }
                }
                RES_XML_START_ELEMENT_TYPE -> {
                    elements += readStartElement(offset, chunkHeader, depth)
                    depth++
                    if (elements.size > maxElements) throw ValidationException("Manifiesto demasiado grande")
                }
                RES_XML_END_ELEMENT_TYPE -> depth = maxOf(0, depth - 1)
                else -> Unit // espacios de nombres, CDATA, etc.
            }
            offset += chunkSize
        }
        return elements
    }

    private fun readStartElement(offset: Int, headerSize: Int, depth: Int): Element {
        val ext = offset + headerSize
        val nameIndex = buffer.getInt(ext + 4)
        val attributeStart = u16(ext + 8)
        val attributeSize = u16(ext + 10)
        val attributeCount = u16(ext + 12)
        if (attributeSize < 20) throw ValidationException("Manifiesto binario corrupto (atributos)")
        val attributes = (0 until attributeCount).map { i ->
            val a = ext + attributeStart + i * attributeSize
            val ns = buffer.getInt(a)
            val name = buffer.getInt(a + 4)
            val raw = buffer.getInt(a + 8)
            val dataType = buffer.get(a + 15).toInt() and 0xFF
            val data = buffer.getInt(a + 16)
            Attribute(
                namespace = string(ns),
                name = string(name) ?: "",
                resourceId = if (name in resourceIds.indices) resourceIds[name] else 0,
                type = dataType,
                data = data,
                stringValue = when {
                    raw >= 0 -> string(raw)
                    dataType == TYPE_STRING -> string(data)
                    else -> null
                },
            )
        }
        return Element(string(nameIndex) ?: "", depth, attributes)
    }

    private fun readStringPool(offset: Int, headerSize: Int, chunkSize: Int): List<String> {
        val count = u32(offset + 8)
        val flags = u32(offset + 16)
        val stringsStart = u32(offset + 20)
        if (count < 0 || count > 200_000) throw ValidationException("Manifiesto binario corrupto (cadenas)")
        val utf8 = flags and UTF8_FLAG != 0
        val offsetsBase = offset + headerSize
        val dataBase = offset + stringsStart
        return List(count) { i ->
            val position = dataBase + buffer.getInt(offsetsBase + i * 4)
            if (position < offset || position >= offset + chunkSize) "" else if (utf8) readUtf8(position) else readUtf16(position)
        }
    }

    private fun readUtf8(start: Int): String {
        var p = start
        // Longitud en UTF-16 (se ignora) y longitud en bytes UTF-8, ambas de 1 o 2 bytes.
        p += if (buffer.get(p).toInt() and 0x80 != 0) 2 else 1
        val first = buffer.get(p).toInt() and 0xFF
        val length: Int
        if (first and 0x80 != 0) {
            length = ((first and 0x7F) shl 8) or (buffer.get(p + 1).toInt() and 0xFF)
            p += 2
        } else {
            length = first
            p += 1
        }
        val bytes = ByteArray(length) { i -> buffer.get(p + i) }
        return String(bytes, Charsets.UTF_8)
    }

    private fun readUtf16(start: Int): String {
        var p = start
        var length = u16(p)
        p += 2
        if (length and 0x8000 != 0) {
            length = ((length and 0x7FFF) shl 16) or u16(p)
            p += 2
        }
        val chars = CharArray(length) { i -> buffer.getChar(p + i * 2) }
        return String(chars)
    }

    private fun string(index: Int): String? = if (index >= 0 && index < strings.size) strings[index] else null
    private fun u16(position: Int): Int = buffer.getShort(position).toInt() and 0xFFFF
    private fun u32(position: Int): Int = buffer.getInt(position)

    companion object {
        const val RES_STRING_POOL_TYPE = 0x0001
        const val RES_XML_TYPE = 0x0003
        const val RES_XML_START_ELEMENT_TYPE = 0x0102
        const val RES_XML_END_ELEMENT_TYPE = 0x0103
        const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
        const val UTF8_FLAG = 1 shl 8

        const val TYPE_REFERENCE = 0x01
        const val TYPE_STRING = 0x03
        const val TYPE_INT_DEC = 0x10
        const val TYPE_INT_HEX = 0x11
        const val TYPE_INT_BOOLEAN = 0x12
    }
}

/** Datos del manifiesto que se validan antes de instalar. */
data class ManifestInfo(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int,
    val targetSdk: Int?,
    val maxSdk: Int?,
    /** Nombre del split; null en la APK base. */
    val splitName: String?,
    val isFeatureSplit: Boolean,
    val configForSplit: String?,
)

object ManifestReader {
    const val ATTR_VERSION_CODE = 0x0101021b
    const val ATTR_VERSION_NAME = 0x0101021c
    const val ATTR_MIN_SDK = 0x0101020c
    const val ATTR_TARGET_SDK = 0x01010270
    const val ATTR_MAX_SDK = 0x01010271
    const val ATTR_VERSION_CODE_MAJOR = 0x01010576
    const val ATTR_IS_FEATURE_SPLIT = 0x0101055b

    /** SDK usado para versiones preliminares («codename») que no son instalables en tablets normales. */
    const val PREVIEW_SDK = 10_000

    fun read(axml: ByteArray): ManifestInfo {
        val elements = BinaryXmlParser(axml).parse()
        val manifest = elements.firstOrNull { it.name == "manifest" && it.depth == 0 }
            ?: throw ValidationException("El manifiesto no contiene el elemento <manifest>")
        val packageName = manifest.plainAttr("package")?.stringValue?.trim()
        if (packageName.isNullOrEmpty()) throw ValidationException("La APK no declara nombre de paquete")
        val versionCode = intValue(manifest.attr(ATTR_VERSION_CODE, "versionCode"))?.toLong()?.and(0xFFFFFFFFL)
            ?: throw ValidationException("La APK no declara versionCode")
        val major = intValue(manifest.attr(ATTR_VERSION_CODE_MAJOR, "versionCodeMajor"))?.toLong() ?: 0L
        val usesSdk = elements.firstOrNull { it.name == "uses-sdk" && it.depth == 1 }
        val minSdk = usesSdk?.attr(ATTR_MIN_SDK, "minSdkVersion")?.let { sdkValue(it) } ?: 1
        val targetSdk = usesSdk?.attr(ATTR_TARGET_SDK, "targetSdkVersion")?.let { sdkValue(it) }
        val maxSdk = usesSdk?.attr(ATTR_MAX_SDK, "maxSdkVersion")?.let { sdkValue(it) }
        val split = manifest.plainAttr("split")?.stringValue?.trim()?.takeIf { it.isNotEmpty() }
        val featureSplit = manifest.attr(ATTR_IS_FEATURE_SPLIT, "isFeatureSplit")?.let { it.type == BinaryXmlParser.TYPE_INT_BOOLEAN && it.data != 0 } ?: false
        return ManifestInfo(
            packageName = packageName,
            versionCode = (major shl 32) or versionCode,
            versionName = manifest.attr(ATTR_VERSION_NAME, "versionName")?.stringValue,
            minSdk = minSdk,
            targetSdk = targetSdk,
            maxSdk = maxSdk,
            splitName = split,
            isFeatureSplit = featureSplit,
            configForSplit = manifest.plainAttr("configForSplit")?.stringValue?.takeIf { it.isNotEmpty() },
        )
    }

    private fun intValue(attribute: BinaryXmlParser.Attribute?): Int? = when {
        attribute == null -> null
        attribute.type == BinaryXmlParser.TYPE_INT_DEC || attribute.type == BinaryXmlParser.TYPE_INT_HEX -> attribute.data
        attribute.type == BinaryXmlParser.TYPE_STRING -> attribute.stringValue?.trim()?.toIntOrNull()
        else -> null
    }

    private fun sdkValue(attribute: BinaryXmlParser.Attribute): Int = when (attribute.type) {
        BinaryXmlParser.TYPE_INT_DEC, BinaryXmlParser.TYPE_INT_HEX -> attribute.data
        BinaryXmlParser.TYPE_STRING -> attribute.stringValue?.trim()?.toIntOrNull() ?: PREVIEW_SDK
        else -> PREVIEW_SDK
    }
}
