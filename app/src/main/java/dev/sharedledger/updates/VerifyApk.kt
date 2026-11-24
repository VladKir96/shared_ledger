package dev.sharedledger.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

internal data class ApkIdentity(
    val packageName: String, val versionName: String, val version: Long, val minimumSdk: Int, val signers: Set<String>
)

internal fun verifyIdentity(
    expected: ApkMetadata, actual: ApkIdentity, installed: ApkIdentity, pinnedSigner: String, sdk: Int
): Boolean = pinnedSigner.matches(Regex("[0-9a-f]{64}")) &&
    expected.isValid() && installed.signers == setOf(pinnedSigner) && actual.signers == setOf(pinnedSigner) &&
    expected.packageName == installed.packageName && actual.packageName == installed.packageName && actual.version == expected.versionCode &&
    actual.versionName == expected.versionName &&
    actual.version > installed.version && actual.minimumSdk == expected.minSdk && actual.minimumSdk <= sdk

internal class VerifyApk(private val context: Context, private val signer: String) {
    fun verify(release: UpdateRelease, apk: File): Boolean {
        if (!apk.isFile || apk.length() != release.size || apk.sha256() != release.metadata.sha256) return false
        // GET_SIGNING_CERTIFICATES asks Android to validate archive signatures and report current signers.
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, signatureFlag)?.identity() ?: return false
        val installed = context.packageManager.getPackageInfo(context.packageName, signatureFlag).identity() ?: return false
        return verifyIdentity(release.metadata, archive, installed, signer, Build.VERSION.SDK_INT)
    }
}

@Suppress("DEPRECATION")
private val signatureFlag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

@Suppress("DEPRECATION")
private fun PackageInfo.identity(): ApkIdentity? {
    val app = applicationInfo ?: return null
    val certificates = if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures
    return ApkIdentity(packageName, versionName.orEmpty(), PackageInfoCompat.getLongVersionCode(this), app.minSdkVersion,
        certificates.orEmpty().map { sha256(it.toByteArray()) }.toSet())
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            digest.update(buffer, 0, size)
        }
    }
    return digest.digest().hex()
}
