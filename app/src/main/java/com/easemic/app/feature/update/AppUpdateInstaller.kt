package com.easemic.app.feature.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * #E123: Pure JVM + Android helper for in-app APK streaming download,
 * directory containment, SHA-256 + archive verification, unknown-sources
 * settings resolution, FileProvider installer launch, and cold-start cache
 * sweeping.
 */
object AppUpdateHelper {
    const val UPDATE_CACHE_DIR = "app-updates"
    const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    const val MAX_APK_DOWNLOAD_BYTES = 250L * 1024L * 1024L // 250 MiB ceiling
    private const val BUFFER_SIZE = 64 * 1024

    const val ACTION_MANAGE_UNKNOWN_APP_SOURCES = "android.settings.MANAGE_UNKNOWN_APP_SOURCES"
    const val ACTION_SECURITY_SETTINGS = "android.settings.SECURITY_SETTINGS"
    const val ACTION_APPLICATION_DETAILS_SETTINGS = "android.settings.APPLICATION_DETAILS_SETTINGS"

    val UNKNOWN_SOURCES_ACTIONS: List<String> = listOf(
        ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        ACTION_SECURITY_SETTINGS,
        ACTION_APPLICATION_DETAILS_SETTINGS,
    )

    private val APK_NAME_REGEX = Regex("^easemic-(\\d{1,9})\\.apk$")
    private val SHA256_HEX_REGEX = Regex("^[0-9a-f]{64}$")
    private val lock = Any()

    data class ArchiveMetadata(
        val packageName: String?,
        val versionCode: Int,
    )

    fun interface ArchiveInspector {
        fun inspect(apkFile: File): ArchiveMetadata?
    }

    data class VerificationResult(
        val isValid: Boolean,
        val reason: String? = null,
    ) {
        companion object {
            fun ok(): VerificationResult = VerificationResult(true, null)
            fun fail(reason: String): VerificationResult = VerificationResult(false, reason)
        }
    }

    fun updateDir(cacheDir: File): File = File(cacheDir, UPDATE_CACHE_DIR)

    fun finalApkFile(updateDir: File, versionCode: Int): File =
        File(updateDir, "easemic-$versionCode.apk")

    fun partApkFile(updateDir: File, versionCode: Int): File =
        File(updateDir, "easemic-$versionCode.apk.part")

    /**
     * Parses `versionCode` from a canonical `easemic-<versionCode>.apk` file name,
     * or returns `-1` for `.part` or non-matching names.
     */
    fun parseApkVersionCode(fileName: String?): Int {
        if (fileName == null) return -1
        val match = APK_NAME_REGEX.matchEntire(fileName) ?: return -1
        val code = match.groupValues[1].toIntOrNull() ?: return -1
        return if (code > 0) code else -1
    }

    fun firstResolvableUnknownSourcesAction(resolves: (String) -> Boolean): String? =
        UNKNOWN_SOURCES_ACTIONS.firstOrNull(resolves)

    fun unknownSourcesActionNeedsPackageUri(action: String): Boolean =
        action == ACTION_MANAGE_UNKNOWN_APP_SOURCES || action == ACTION_APPLICATION_DETAILS_SETTINGS

    /**
     * Returns true iff [targetFile] resolves canonically as a direct child of [updateDir].
     * Prevents any path outside `cache/app-updates/` from being deleted or exposed via FileProvider.
     */
    fun isContainedInUpdateDir(updateDir: File?, targetFile: File?): Boolean {
        if (updateDir == null || targetFile == null) return false
        return try {
            val canonicalDir = updateDir.canonicalFile
            val canonicalFile = targetFile.canonicalFile
            canonicalFile.parentFile == canonicalDir
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Sweeps [updateDir] (`cache/app-updates/`):
     * - deletes all `.part` or unrecognized files;
     * - deletes any `easemic-<code>.apk` whose `code <= installedVersionCode`;
     * - preserves only `easemic-<code>.apk` where `code > installedVersionCode`.
     */
    fun sweepDirectory(updateDir: File?, installedVersionCode: Int): Int = synchronized(lock) {
        if (updateDir == null || !updateDir.exists() || !updateDir.isDirectory) return 0
        val files = updateDir.listFiles() ?: return 0
        var deleted = 0
        for (file in files) {
            val code = if (file.isFile) parseApkVersionCode(file.name) else -1
            if (code <= 0 || code <= installedVersionCode) {
                if (file.deleteRecursively()) {
                    deleted += 1
                }
            }
        }
        deleted
    }

    /** Computes lowercase 64-char hex SHA-256 of [file] in 64 KiB streaming chunks. */
    fun computeFileSha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { b -> String.format(Locale.US, "%02x", b.toInt() and 0xff) }
    }

    /**
     * Verifies [apkFile] against containment inside [updateDir], non-empty size,
     * optional [expectedSizeBytes], optional [expectedSha256], package name, and
     * [expectedVersionCode]. Deletes [apkFile] immediately if any content check
     * fails (only after confirming [isContainedInUpdateDir]).
     */
    fun verifyFileAndArchive(
        updateDir: File?,
        apkFile: File?,
        expectedVersionCode: Int,
        expectedSizeBytes: Long?,
        expectedSha256: String?,
        expectedPackageName: String,
        inspector: ArchiveInspector,
    ): VerificationResult = synchronized(lock) {
        if (!isContainedInUpdateDir(updateDir, apkFile) || apkFile == null) {
            return VerificationResult.fail("invalid_path")
        }
        if (!apkFile.exists() || !apkFile.isFile) {
            return VerificationResult.fail("missing_file")
        }
        val actualLength = apkFile.length()
        if (actualLength <= 0L || actualLength > MAX_APK_DOWNLOAD_BYTES) {
            apkFile.delete()
            return VerificationResult.fail("size_mismatch")
        }
        if (expectedSizeBytes != null && expectedSizeBytes > 0L && actualLength != expectedSizeBytes) {
            apkFile.delete()
            return VerificationResult.fail("size_mismatch")
        }
        if (!expectedSha256.isNullOrBlank()) {
            val normalizedExpectedSha = expectedSha256.trim().lowercase(Locale.US)
            if (!SHA256_HEX_REGEX.matches(normalizedExpectedSha)) {
                apkFile.delete()
                return VerificationResult.fail("invalid_expected_sha256")
            }
            val actualSha = try {
                computeFileSha256Hex(apkFile)
            } catch (_: Throwable) {
                apkFile.delete()
                return VerificationResult.fail("sha256_read_error")
            }
            if (actualSha != normalizedExpectedSha) {
                apkFile.delete()
                return VerificationResult.fail("sha256_mismatch")
            }
        }
        val meta = try {
            inspector.inspect(apkFile)
        } catch (_: Throwable) {
            null
        }
        if (meta == null || meta.packageName.isNullOrBlank()) {
            apkFile.delete()
            return VerificationResult.fail("invalid_archive")
        }
        if (meta.packageName != expectedPackageName) {
            apkFile.delete()
            return VerificationResult.fail("package_mismatch")
        }
        if (meta.versionCode != expectedVersionCode) {
            apkFile.delete()
            return VerificationResult.fail("version_mismatch")
        }
        VerificationResult.ok()
    }

    /**
     * Streams [downloadUrl] into [partFile] and renames it atomically to [finalFile].
     * Aborts and deletes [partFile] if [shouldCancel] returns true or byte cap is exceeded.
     */
    fun streamApkDownload(
        downloadUrl: String,
        updateDir: File,
        partFile: File,
        finalFile: File,
        expectedSizeBytes: Long?,
        http: OkHttpClient = defaultDownloadHttpClient(),
        shouldCancel: () -> Boolean = { false },
        onProgress: (bytesReceived: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ) {
        if (!updateDir.exists()) updateDir.mkdirs()
        check(isContainedInUpdateDir(updateDir, partFile) && isContainedInUpdateDir(updateDir, finalFile)) {
            "APK target files must reside inside ${updateDir.absolutePath}"
        }
        partFile.delete()
        val request = Request.Builder()
            .url(downloadUrl)
            .header("Accept", APK_MIME_TYPE)
            .get()
            .build()
        val call = http.newCall(request)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP_${response.code}")
                }
                val body = response.body ?: throw IOException("EMPTY_BODY")
                val headerTotal = body.contentLength().takeIf { it > 0L }
                val totalBytes = headerTotal ?: expectedSizeBytes?.takeIf { it > 0L } ?: 0L
                val maxAllowed = if (expectedSizeBytes != null && expectedSizeBytes > 0L) {
                    minOf(expectedSizeBytes, MAX_APK_DOWNLOAD_BYTES)
                } else {
                    MAX_APK_DOWNLOAD_BYTES
                }
                if (headerTotal != null && headerTotal > maxAllowed) {
                    throw IOException("APK_TOO_LARGE")
                }
                onProgress(0L, totalBytes)
                val buffer = ByteArray(BUFFER_SIZE)
                var received = 0L
                body.byteStream().use { input ->
                    FileOutputStream(partFile).use { output ->
                        while (true) {
                            if (shouldCancel()) {
                                call.cancel()
                                throw IOException("CANCELLED")
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            if (read > 0) {
                                received += read
                                if (received > maxAllowed) {
                                    call.cancel()
                                    throw IOException("APK_TOO_LARGE")
                                }
                                output.write(buffer, 0, read)
                                onProgress(received, if (totalBytes > 0L) totalBytes else received)
                            }
                        }
                        output.flush()
                    }
                }
                if (received <= 0L) {
                    throw IOException("EMPTY_BODY")
                }
                if (headerTotal != null && received != headerTotal) {
                    throw IOException("TRUNCATED_BODY")
                }
            }
            synchronized(lock) {
                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }
            }
        } catch (t: Throwable) {
            partFile.delete()
            throw t
        }
    }

    fun defaultDownloadHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
}

object AppUpdateInstaller {
    private const val FILE_PROVIDER_SUFFIX = ".update-fileprovider"

    fun updateDir(context: Context): File = AppUpdateHelper.updateDir(context.cacheDir)

    fun inspectArchive(context: Context, apkFile: File): AppUpdateHelper.ArchiveMetadata? {
        val pm = context.packageManager
        val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
        } ?: return null
        return AppUpdateHelper.ArchiveMetadata(
            packageName = archiveInfo.packageName,
            versionCode = PackageInfoCompat.getLongVersionCode(archiveInfo).toInt(),
        )
    }

    fun verifyDownloadedApk(
        context: Context,
        apkFile: File,
        release: AppRelease,
        inspector: AppUpdateHelper.ArchiveInspector = AppUpdateHelper.ArchiveInspector { inspectArchive(context, it) },
    ): AppUpdateHelper.VerificationResult =
        AppUpdateHelper.verifyFileAndArchive(
            updateDir = updateDir(context),
            apkFile = apkFile,
            expectedVersionCode = release.versionCode,
            expectedSizeBytes = release.sizeBytes,
            expectedSha256 = release.sha256,
            expectedPackageName = context.packageName,
            inspector = inspector,
        )

    fun hasVerifiedCachedApk(
        context: Context,
        release: AppRelease,
        inspector: AppUpdateHelper.ArchiveInspector = AppUpdateHelper.ArchiveInspector { inspectArchive(context, it) },
    ): File? {
        val dir = updateDir(context)
        val file = AppUpdateHelper.finalApkFile(dir, release.versionCode)
        if (!file.isFile) return null
        val verdict = verifyDownloadedApk(context, file, release, inspector)
        return if (verdict.isValid) file else null
    }

    fun canRequestPackageInstalls(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openUnknownAppSourcesSettings(context: Context, currentActivity: Activity? = null) {
        fun intentFor(action: String): Intent = Intent(action).apply {
            if (AppUpdateHelper.unknownSourcesActionNeedsPackageUri(action)) {
                data = "package:${context.packageName}".toUri()
            }
        }
        val pm = context.packageManager
        val action = AppUpdateHelper.firstResolvableUnknownSourcesAction { candidate ->
            intentFor(candidate).resolveActivity(pm) != null
        } ?: return
        val targetIntent = intentFor(action)
        if (currentActivity != null) {
            currentActivity.startActivity(targetIntent)
        } else {
            targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(targetIntent)
        }
    }

    fun installUpdateApk(context: Context, apkFile: File, currentActivity: Activity? = null) {
        val dir = updateDir(context)
        require(AppUpdateHelper.isContainedInUpdateDir(dir, apkFile)) {
            "Update APK must reside inside ${dir.absolutePath}"
        }
        val canonical = apkFile.canonicalFile
        require(AppUpdateHelper.parseApkVersionCode(canonical.name) > 0) {
            "Update APK has non-canonical filename: ${canonical.name}"
        }
        require(canonical.isFile) {
            "Update APK file does not exist: ${canonical.absolutePath}"
        }
        val authority = "${context.packageName}$FILE_PROVIDER_SUFFIX"
        val contentUri = FileProvider.getUriForFile(context, authority, canonical)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, AppUpdateHelper.APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (currentActivity != null) {
            currentActivity.startActivity(intent)
        } else {
            context.startActivity(intent)
        }
    }

    fun sweepUpdateApks(context: Context, installedVersionCode: Int): Int =
        AppUpdateHelper.sweepDirectory(updateDir(context), installedVersionCode)
}
