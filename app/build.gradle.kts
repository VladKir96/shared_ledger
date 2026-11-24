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
val releaseSigner = releaseStore?.let { path ->
    val keys = requireNotNull(releaseKeys)
    val store = KeyStore.getInstance(path, keys.getProperty("storePassword").toCharArray())
    require(store.isKeyEntry(keys.getProperty("keyAlias"))) { "The release alias must identify a signing key." }
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
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SUPABASE_URL", config("SUPABASE_URL"))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", config("SUPABASE_PUBLISHABLE_KEY"))
        buildConfigField("String", "UPDATE_GITHUB_REPOSITORY", "\"$updateRepository\"")
    }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    if (releaseKeys != null) signingConfigs.create("stableRelease") {
        storeFile = releaseStore
        storePassword = releaseKeys.getProperty("storePassword")
        keyAlias = releaseKeys.getProperty("keyAlias")
        keyPassword = releaseKeys.getProperty("keyPassword")
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
