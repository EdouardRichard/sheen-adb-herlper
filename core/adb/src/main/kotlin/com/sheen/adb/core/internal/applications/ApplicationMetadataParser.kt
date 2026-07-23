package com.sheen.adb.core.internal.applications

import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import net.dongliu.apk.parser.ByteArrayApkFile
import net.dongliu.apk.parser.bean.AdaptiveIcon
import net.dongliu.apk.parser.bean.Icon

internal const val MAX_APPLICATION_APK_BYTES: Int = 32 * 1024 * 1024
internal const val MAX_APPLICATION_ICON_BYTES: Int = 1024 * 1024

internal enum class ApplicationMetadataParseFailure {
    APK_TOO_LARGE,
    MALFORMED_ARCHIVE,
    UNSAFE_ARCHIVE,
    SPLIT_APK_UNSUPPORTED,
    PARSE_FAILED,
}

internal enum class ParsedApplicationIconKind {
    RASTER,
    ADAPTIVE_FOREGROUND_FALLBACK,
}

internal data class ParsedApplicationIcon(
    val encodedBytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val kind: ParsedApplicationIconKind,
)

internal data class ParsedApplicationMetadata(
    val packageName: String,
    val displayName: String?,
    val icon: ParsedApplicationIcon?,
)

internal sealed interface ApplicationMetadataParseResult {
    data class Success(val metadata: ParsedApplicationMetadata) : ApplicationMetadataParseResult
    data class Failure(val reason: ApplicationMetadataParseFailure) : ApplicationMetadataParseResult
}

internal sealed interface DecodedApkIcon {
    data class Raster(
        val path: String,
        val density: Int,
        val bytes: ByteArray?,
    ) : DecodedApkIcon

    data class Adaptive(
        val foreground: Raster?,
        val background: Raster?,
    ) : DecodedApkIcon
}

internal data class DecodedApkMetadata(
    val packageName: String,
    val splitName: String?,
    val label: String?,
    val icons: List<DecodedApkIcon>,
)

internal fun interface ApkMetadataDecoder {
    fun decode(apkBytes: ByteArray, locale: Locale): DecodedApkMetadata
}

/**
 * Security boundary around the archived third-party APK parser.
 *
 * Only bounded in-memory APK bytes and project-owned values cross this adapter. The archive is
 * inspected before the dependency receives it, and no path or decoded application context is
 * logged or persisted here.
 */
internal class ApplicationMetadataParser(
    private val decoder: ApkMetadataDecoder = DongliuApkMetadataDecoder,
) {
    fun parse(
        apkBytes: ByteArray,
        preferredLocaleTags: List<String>,
    ): ApplicationMetadataParseResult {
        if (apkBytes.size > MAX_APPLICATION_APK_BYTES) {
            return ApplicationMetadataParseResult.Failure(ApplicationMetadataParseFailure.APK_TOO_LARGE)
        }

        when (inspectArchive(apkBytes)) {
            ArchiveInspection.SAFE -> Unit
            ArchiveInspection.MALFORMED -> return ApplicationMetadataParseResult.Failure(
                ApplicationMetadataParseFailure.MALFORMED_ARCHIVE,
            )
            ArchiveInspection.UNSAFE -> return ApplicationMetadataParseResult.Failure(
                ApplicationMetadataParseFailure.UNSAFE_ARCHIVE,
            )
        }

        val locales = preferredLocaleTags
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(Locale::forLanguageTag)
            .filter { it.language.isNotEmpty() }
            .distinctBy(Locale::toLanguageTag)
            .toList()
            .ifEmpty { listOf(Locale.US) }

        return try {
            var packageName: String? = null
            var displayName: String? = null
            var icon: ParsedApplicationIcon? = null

            for (locale in locales) {
                val decoded = decoder.decode(apkBytes, locale)
                if (!decoded.splitName.isNullOrBlank()) {
                    return ApplicationMetadataParseResult.Failure(
                        ApplicationMetadataParseFailure.SPLIT_APK_UNSUPPORTED,
                    )
                }
                val decodedPackage = decoded.packageName.trim()
                if (decodedPackage.isEmpty() || packageName != null && packageName != decodedPackage) {
                    return ApplicationMetadataParseResult.Failure(ApplicationMetadataParseFailure.PARSE_FAILED)
                }
                packageName = decodedPackage
                if (displayName == null) {
                    displayName = decoded.label?.trim()?.takeIf(String::isNotEmpty)
                }
                if (icon == null) {
                    icon = selectIcon(decoded.icons)
                }
                if (displayName != null && icon != null) break
            }

            val verifiedPackage = packageName
                ?: return ApplicationMetadataParseResult.Failure(ApplicationMetadataParseFailure.PARSE_FAILED)
            ApplicationMetadataParseResult.Success(
                ParsedApplicationMetadata(
                    packageName = verifiedPackage,
                    displayName = displayName,
                    icon = icon,
                ),
            )
        } catch (_: Exception) {
            ApplicationMetadataParseResult.Failure(ApplicationMetadataParseFailure.PARSE_FAILED)
        } catch (_: LinkageError) {
            ApplicationMetadataParseResult.Failure(ApplicationMetadataParseFailure.PARSE_FAILED)
        }
    }

    private fun selectIcon(icons: List<DecodedApkIcon>): ParsedApplicationIcon? = icons
        .asSequence()
        .mapNotNull { icon ->
            when (icon) {
                is DecodedApkIcon.Raster -> icon.toParsed(ParsedApplicationIconKind.RASTER)
                is DecodedApkIcon.Adaptive -> icon.foreground?.toParsed(
                    ParsedApplicationIconKind.ADAPTIVE_FOREGROUND_FALLBACK,
                )
            }
        }
        .maxByOrNull { it.sourceDensity }
        ?.icon

    private fun DecodedApkIcon.Raster.toParsed(kind: ParsedApplicationIconKind): IconCandidate? {
        val data = bytes ?: return null
        if (data.isEmpty() || data.size > MAX_APPLICATION_ICON_BYTES) return null
        val image = readImageInfo(data) ?: return null
        return IconCandidate(
            sourceDensity = density,
            icon = ParsedApplicationIcon(
                encodedBytes = data.copyOf(),
                mimeType = image.mimeType,
                width = image.width,
                height = image.height,
                kind = kind,
            ),
        )
    }

    private fun inspectArchive(bytes: ByteArray): ArchiveInspection {
        if (bytes.isEmpty()) return ArchiveInspection.MALFORMED
        var entryCount = 0
        var expandedBytes = 0L
        var hasManifest = false
        val names = HashSet<String>()
        val buffer = ByteArray(8192)
        return try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount += 1
                    if (entryCount > MAX_ARCHIVE_ENTRIES || !isSafeEntryName(entry.name) || !names.add(entry.name)) {
                        return ArchiveInspection.UNSAFE
                    }
                    if (entry.name == "AndroidManifest.xml") hasManifest = true
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        expandedBytes += count
                        val ratioLimit = bytes.size.toLong() * MAX_COMPRESSION_RATIO + COMPRESSION_RATIO_GRACE_BYTES
                        if (expandedBytes > MAX_EXPANDED_ARCHIVE_BYTES || expandedBytes > ratioLimit) {
                            return ArchiveInspection.UNSAFE
                        }
                    }
                    zip.closeEntry()
                }
            }
            if (entryCount == 0 || !hasManifest) ArchiveInspection.MALFORMED else ArchiveInspection.SAFE
        } catch (_: ZipException) {
            ArchiveInspection.MALFORMED
        } catch (_: RuntimeException) {
            ArchiveInspection.MALFORMED
        }
    }

    private fun isSafeEntryName(name: String): Boolean {
        if (name.isEmpty() || name.startsWith('/') || name.startsWith('\\') || '\\' in name) return false
        if (name.length > MAX_ENTRY_NAME_LENGTH || ':' in name || '\u0000' in name) return false
        return name.split('/').none { it == ".." || it == "." }
    }

    private data class IconCandidate(val sourceDensity: Int, val icon: ParsedApplicationIcon)
    private data class ImageInfo(val mimeType: String, val width: Int, val height: Int)

    private fun readImageInfo(bytes: ByteArray): ImageInfo? =
        readPngInfo(bytes) ?: readWebpInfo(bytes) ?: readJpegInfo(bytes)

    private fun readPngInfo(bytes: ByteArray): ImageInfo? {
        if (bytes.size < 24 || !bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) return null
        val width = bytes.readIntBigEndian(16)
        val height = bytes.readIntBigEndian(20)
        return dimensions("image/png", width, height)
    }

    private fun readWebpInfo(bytes: ByteArray): ImageInfo? {
        if (bytes.size < 30 || bytes.ascii(0, 4) != "RIFF" || bytes.ascii(8, 4) != "WEBP") return null
        return when (bytes.ascii(12, 4)) {
            "VP8X" -> dimensions(
                "image/webp",
                1 + bytes.readUInt24LittleEndian(24),
                1 + bytes.readUInt24LittleEndian(27),
            )
            "VP8L" -> {
                if (bytes.size < 25 || bytes[20].toInt() and 0xff != 0x2f) return null
                val bits = bytes.readIntLittleEndian(21)
                dimensions("image/webp", (bits and 0x3fff) + 1, ((bits ushr 14) and 0x3fff) + 1)
            }
            else -> null
        }
    }

    private fun readJpegInfo(bytes: ByteArray): ImageInfo? {
        if (bytes.size < 4 || bytes[0].toInt() and 0xff != 0xff || bytes[1].toInt() and 0xff != 0xd8) return null
        var offset = 2
        while (offset + 3 < bytes.size) {
            if (bytes[offset].toInt() and 0xff != 0xff) return null
            val marker = bytes[offset + 1].toInt() and 0xff
            offset += 2
            if (marker == 0xd9 || marker == 0xda) return null
            if (marker == 0x01 || marker in 0xd0..0xd7) continue
            if (offset + 2 > bytes.size) return null
            val length = bytes.readUnsignedShortBigEndian(offset)
            if (length < 2 || offset + length > bytes.size) return null
            if (marker in JPEG_START_OF_FRAME_MARKERS && length >= 7) {
                return dimensions(
                    "image/jpeg",
                    bytes.readUnsignedShortBigEndian(offset + 5),
                    bytes.readUnsignedShortBigEndian(offset + 3),
                )
            }
            offset += length
        }
        return null
    }

    private fun dimensions(mimeType: String, width: Int, height: Int): ImageInfo? =
        if (width in 1..MAX_ICON_DIMENSION && height in 1..MAX_ICON_DIMENSION) {
            ImageInfo(mimeType, width, height)
        } else {
            null
        }

    private enum class ArchiveInspection { SAFE, MALFORMED, UNSAFE }

    private companion object {
        const val MAX_ARCHIVE_ENTRIES = 4096
        const val MAX_ENTRY_NAME_LENGTH = 512
        const val MAX_EXPANDED_ARCHIVE_BYTES = 64L * 1024 * 1024
        const val MAX_COMPRESSION_RATIO = 100L
        const val COMPRESSION_RATIO_GRACE_BYTES = 1024L * 1024
        const val MAX_ICON_DIMENSION = 8192
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val JPEG_START_OF_FRAME_MARKERS = setOf(
            0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf,
        )
    }
}

private object DongliuApkMetadataDecoder : ApkMetadataDecoder {
    override fun decode(apkBytes: ByteArray, locale: Locale): DecodedApkMetadata =
        ByteArrayApkFile(apkBytes).use { apk ->
            apk.preferredLocale = locale
            val meta = apk.apkMeta
            DecodedApkMetadata(
                packageName = meta.packageName.orEmpty(),
                splitName = meta.split,
                label = meta.label,
                icons = apk.allIcons.mapNotNull { icon ->
                    when (icon) {
                        is AdaptiveIcon -> DecodedApkIcon.Adaptive(
                            foreground = icon.foreground?.toDecoded(),
                            background = icon.background?.toDecoded(),
                        )
                        is Icon -> icon.toDecoded()
                        else -> null
                    }
                },
            )
        }

    private fun Icon.toDecoded(): DecodedApkIcon.Raster = DecodedApkIcon.Raster(
        path = path.orEmpty(),
        density = density,
        bytes = data,
    )
}

private fun ByteArray.readIntBigEndian(offset: Int): Int =
    (this[offset].toInt() and 0xff shl 24) or
        (this[offset + 1].toInt() and 0xff shl 16) or
        (this[offset + 2].toInt() and 0xff shl 8) or
        (this[offset + 3].toInt() and 0xff)

private fun ByteArray.readIntLittleEndian(offset: Int): Int =
    (this[offset].toInt() and 0xff) or
        (this[offset + 1].toInt() and 0xff shl 8) or
        (this[offset + 2].toInt() and 0xff shl 16) or
        (this[offset + 3].toInt() and 0xff shl 24)

private fun ByteArray.readUInt24LittleEndian(offset: Int): Int =
    (this[offset].toInt() and 0xff) or
        (this[offset + 1].toInt() and 0xff shl 8) or
        (this[offset + 2].toInt() and 0xff shl 16)

private fun ByteArray.readUnsignedShortBigEndian(offset: Int): Int =
    (this[offset].toInt() and 0xff shl 8) or (this[offset + 1].toInt() and 0xff)

private fun ByteArray.ascii(offset: Int, length: Int): String =
    String(this, offset, length, Charsets.US_ASCII)
