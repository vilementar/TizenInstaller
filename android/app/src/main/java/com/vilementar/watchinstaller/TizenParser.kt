package com.vilementar.watchinstaller

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.zip.ZipInputStream

data class TizenAppInfo(
    val name: String,
    val packageId: String,
    val version: String,
    val isWatchface: Boolean,
    val iconBitmap: Bitmap?,
    val rawBytes: ByteArray,
    val fileName: String,
    val isWgt: Boolean
) {
    val isWatchFace: Boolean get() = isWatchface
}

object TizenParser {
    suspend fun parsePackage(inputStream: InputStream, fileName: String): TizenAppInfo = withContext(Dispatchers.IO) {
        val rawBytes = inputStream.readBytes()
        val isWgt = fileName.endsWith(".wgt", ignoreCase = true)
        
        var appName = fileName.substringBeforeLast(".")
        var packageId = "Unknown"
        var version = "1.0.0"
        var isWatchface = false
        var iconPath = ""
        var iconBitmap: Bitmap? = null

        // 1. First pass: Search for config.xml or tizen-manifest.xml
        ZipInputStream(rawBytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "config.xml" || entry.name == "tizen-manifest.xml") {
                    val xmlContent = zis.readBytes().toString(Charsets.UTF_8)
                    val meta = parseXmlMetadata(xmlContent, isWgt)
                    meta.name?.let { appName = it }
                    meta.packageId?.let { packageId = it }
                    meta.version?.let { version = it }
                    isWatchface = meta.isWatchface
                    meta.icon?.let { iconPath = it }
                    break
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // 2. Second pass: Search for icon
        if (iconPath.isNotEmpty()) {
            ZipInputStream(rawBytes.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == iconPath || entry.name.endsWith(iconPath)) {
                        val iconBytes = zis.readBytes()
                        iconBitmap = BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size)
                        break
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        // Fallback: look for any icon
        if (iconBitmap == null) {
            ZipInputStream(rawBytes.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val n = entry.name.lowercase()
                    if ((n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".jpg")) &&
                        (n.contains("icon") || n.contains("logo") || n.contains("shared/res/"))) {
                        val iconBytes = zis.readBytes()
                        iconBitmap = BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size)
                        if (iconBitmap != null) break
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        return@withContext TizenAppInfo(
            name = appName,
            packageId = packageId,
            version = version,
            isWatchface = isWatchface,
            iconBitmap = iconBitmap,
            rawBytes = rawBytes,
            fileName = fileName,
            isWgt = isWgt
        )
    }

    private data class ParsedMeta(
        val name: String?,
        val packageId: String?,
        val version: String?,
        val icon: String?,
        val isWatchface: Boolean
    )

    private fun parseXmlMetadata(xmlString: String, isWgt: Boolean): ParsedMeta {
        var name: String? = null
        var packageId: String? = null
        var version: String? = null
        var icon: String? = null
        var isWatchface = false

        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(xmlString.reader())

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (isWgt) {
                            if (tagName == "widget") {
                                packageId = parser.getAttributeValue(null, "id")
                                version = parser.getAttributeValue(null, "version")
                            } else if (tagName == "name") {
                                name = parser.nextText()
                            } else if (tagName == "icon") {
                                icon = parser.getAttributeValue(null, "src")
                            }
                        } else {
                            if (tagName == "manifest") {
                                packageId = parser.getAttributeValue(null, "package")
                                version = parser.getAttributeValue(null, "version")
                            } else if (tagName == "watch-application") {
                                isWatchface = true
                            } else if (tagName == "label") {
                                name = parser.nextText()
                            } else if (tagName == "icon") {
                                icon = parser.nextText()
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (xmlString.contains("watchface") || xmlString.contains("watch-application")) {
            isWatchface = true
        }

        return ParsedMeta(name, packageId, version, icon, isWatchface)
    }
}