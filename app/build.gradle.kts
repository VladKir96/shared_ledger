import java.util.Properties
import java.util.Base64
import java.security.KeyStore
import java.security.MessageDigest

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

if (file("google-services.json").isFile) apply(plugin = "com.google.gms.google-services")

val updates = Properties().apply {
    rootProject.file("updates.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
fun updateSetting(name: String) = providers.environmentVariable(name).orNull ?: updates.getProperty(name, "").trim()
val updateRepository = updateSetting("UPDATE_GITHUB_REPOSITORY")
require(updateRepository.isEmpty() || updateRepository.matches(Regex("[A-Za-z0-9-]+/[A-Za-z0-9_.-]+"))) {
    "UPDATE_GITHUB_REPOSITORY must be OWNER/REPOSITORY."
}
val updateManifest = if (updateRepository.isEmpty()) "" else "https://github.com/$updateRepository/releases/latest/download/latest.json"
val configuredManifest = updateSetting("UPDATE_MANIFEST_URL")
require(configuredManifest.isEmpty() || configuredManifest == updateManifest) { "UPDATE_MANIFEST_URL must match the configured GitHub repository." }
val distribution = providers.gradleProperty("sharedLedgerDistribution").orNull == "true"
val releaseCode = providers.gradleProperty("sharedLedgerReleaseVersionCode").orNull
val releaseName = providers.gradleProperty("sharedLedgerReleaseVersionName").orNull
require((releaseCode == null) == (releaseName == null)) { "Set both Android release versions together." }
val buildCode = (releaseCode ?: providers.gradleProperty("sharedLedgerBaseVersionCode").get()).toInt()
val buildName = releaseName ?: providers.gradleProperty("sharedLedgerBaseVersionName").get()
require(buildCode in 1..2_100_000_000 && buildName.matches(Regex("(0|[1-9][0-9]{0,18})\\.(0|[1-9][0-9]{0,18})\\.(0|[1-9][0-9]{0,18})")))
val debugUpdateSigner = updateSetting("UPDATE_SIGNER_SHA256").lowercase()
require(debugUpdateSigner.isEmpty() || debugUpdateSigner.matches(Regex("[0-9a-f]{64}")))
val signingFile = providers.environmentVariable("RELEASE_SIGNING_FILE").orNull?.let { file(it).canonicalFile }
val releaseKeys = signingFile?.let { path ->
    require(!path.toPath().startsWith(rootDir.canonicalFile.toPath())) { "Store release signing configuration outside the repository." }
    Properties().apply { path.inputStream().use(::load) }
}
val releaseStore = releaseKeys?.let { keys ->
    signingFile!!.resolveSibling(keys.getProperty("storeFile")).canonicalFile.also { path ->
        require(!path.toPath().startsWith(rootDir.canonicalFile.toPath())) { "Store the release keystore outside the repository." }
    }
}
val storeSecret = providers.environmentVariable("SHAREDLEDGER_STORE_PASSWORD").orNull ?: releaseKeys?.getProperty("storePassword")
val keySecret = providers.environmentVariable("SHAREDLEDGER_KEY_PASSWORD").orNull ?: releaseKeys?.getProperty("keyPassword")
val releaseSigner = releaseStore?.let { path ->
    val keys = requireNotNull(releaseKeys)
    val store = KeyStore.getInstance(path, requireNotNull(storeSecret) { "Release store password is unavailable." }.toCharArray())
    require(store.getKey(keys.getProperty("keyAlias"), requireNotNull(keySecret).toCharArray()) is java.security.PrivateKey) {
        "The release alias must identify a signing key."
    }
    val certificate = store.getCertificate(keys.getProperty("keyAlias"))
    MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
}.orEmpty()

val cloud = Properties().apply {
    rootProject.file("cloud.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
fun config(name: String): String {
    val value = providers.environmentVariable(name).orNull ?: cloud.getProperty(name, "")
    require(value.none { it in "\\\"\r\n" }) { "Invalid characters in " + name }
    if (name == "SUPABASE_PUBLISHABLE_KEY" && value.isNotBlank()) {
        require(value.startsWith("sb_publishable_") || isLegacyAnonKey(value)) {
            "Use a Supabase publishable or legacy anon key."
        }
    }
    return "\"" + value + "\""
}

if (distribution) {
    require(releaseName != null && releaseSigner.isNotEmpty() && updateManifest.isNotEmpty()) {
        "Distribution requires release versions, signing, and a GitHub repository."
    }
    require(config("SUPABASE_URL").startsWith("\"https://") && config("SUPABASE_PUBLISHABLE_KEY") != "\"\"") {
        "Distribution requires the Supabase HTTPS URL and publishable key."
    }
    require(file("google-services.json").isFile) { "Distribution requires app/google-services.json for dev.sharedledger." }
}

fun isLegacyAnonKey(value: String): Boolean = runCatching {
    val payload = String(Base64.getUrlDecoder().decode(value.split(".")[1]), Charsets.UTF_8)
    Regex("""["]role["]\s*:\s*["]anon["]""").containsMatchIn(payload)
}.getOrDefault(false)

android {
    namespace = "dev.sharedledger"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.sharedledger"
        minSdk = 26
        targetSdk = 35
        versionCode = buildCode
        versionName = buildName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SUPABASE_URL", config("SUPABASE_URL"))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", config("SUPABASE_PUBLISHABLE_KEY"))
        buildConfigField("String", "UPDATE_GITHUB_REPOSITORY", "\"$updateRepository\"")
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$updateManifest\"")
    }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    if (releaseKeys != null) signingConfigs.create("stableRelease") {
        storeFile = releaseStore
        storePassword = storeSecret
        keyAlias = releaseKeys.getProperty("keyAlias")
        keyPassword = keySecret
    }
    buildTypes {
        debug { buildConfigField("String", "UPDATE_SIGNER_SHA256", "\"$debugUpdateSigner\"") }
        release {
            signingConfig = signingConfigs.findByName("stableRelease")
            buildConfigField("String", "UPDATE_SIGNER_SHA256", "\"$releaseSigner\"")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
kotlin { jvmToolchain(17) }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.dagger:hilt-android:2.56.2")
    ksp("com.google.dagger:hilt-compiler:2.56.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.firebase:firebase-messaging:26.0.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:2.7.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
