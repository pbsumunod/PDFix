import java.util.Properties
import java.util.Base64
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val publisher = Properties().apply { rootProject.file("publisher.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) } }
val signing = Properties().apply { rootProject.file("signing.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) } }
val releaseVersion = Properties().apply { rootProject.file("release-version.properties").inputStream().use { load(it) } }
fun setting(key: String, fallback: String = "") = publisher.getProperty(key, fallback)
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
val hasSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !signing.getProperty(it).isNullOrBlank() }

android {
    namespace = "com.sumclartech.paperlane"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.sumclartech.pdfix"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersion.getProperty("versionCode").toInt()
        versionName = releaseVersion.getProperty("versionName")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testProguardFiles("test-proguard-rules.pro")
        resValue("string", "app_name", setting("appName", "PDFix"))
        buildConfigField("String", "APP_NAME", quoted(setting("appName", "PDFix")))
        buildConfigField("String", "PLAY_PRODUCT_ID", quoted(setting("playProductId", "pdfix_lifetime")))
        buildConfigField("String", "PLAY_BILLING_PUBLIC_KEY", quoted(setting("playBillingPublicKey")))
        buildConfigField("String", "PRIVACY_URL", quoted(setting("privacyUrl")))
        buildConfigField("String", "SUPPORT_EMAIL", quoted(setting("supportEmail", "sumclartech@gmail.com")))
    }
    signingConfigs {
        if (hasSigning) create("publisher") {
            storeFile = rootProject.file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-test" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigning) signingConfig = signingConfigs.getByName("publisher")
        }
        create("validation") {
            initWith(getByName("release"))
            applicationIdSuffix = ".validation"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
        create("bootstrap") {
            initWith(getByName("release"))
            versionNameSuffix = "-setup"
            matchingFallbacks += "release"
            // First signed internal-track upload establishes the production app in Play.
            // Empty product/key deliberately disable checkout even if Console is configured.
            buildConfigField("String", "PLAY_PRODUCT_ID", quoted(""))
            buildConfigField("String", "PLAY_BILLING_PUBLIC_KEY", quoted(""))
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += setOf(
        "META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt",
        "META-INF/NOTICE", "META-INF/NOTICE.txt", "/META-INF/{AL2.0,LGPL2.1}",
        // PDFBox uses Bouncy Castle for conventional PDF encryption, not these
        // post-quantum algorithm lookup tables (about 4 MB compressed).
        "org/bouncycastle/pqc/crypto/picnic/*.properties",
        "org/bouncycastle/pqc/crypto/sike/*.properties"
    ) }
    testOptions { unitTests.isReturnDefaultValues = true }
    testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")
    lint { abortOnError = true; checkReleaseBuilds = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    implementation("com.android.billingclient:billing:8.3.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.09.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("com.google.errorprone:error_prone_annotations:2.41.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
tasks.register("verifyPublishingConfiguration") {
    group = "verification"
    doLast {
        val missing = mutableListOf<String>()
        if (!hasSigning) missing += "Upload signing configuration in signing.properties"
        if (hasSigning && !rootProject.file(signing.getProperty("storeFile")).isFile) missing += "Upload keystore file"
        val validBillingKey = runCatching {
            val encoded = Base64.getDecoder().decode(setting("playBillingPublicKey").replace(Regex("\\s"), ""))
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(encoded))
        }.isSuccess
        if (!validBillingKey) missing += "Valid Play Console RSA app licensing public key"
        if (!setting("privacyUrl").startsWith("https://")) missing += "Published HTTPS privacy policy URL"
        if (setting("playProductConfigured") != "true") missing += "Active one-time product and price in Play Console"
        check(missing.isEmpty()) { "Complete release configuration:\n" + missing.joinToString("\n") { " - $it" } }
    }
}
tasks.register("verifyBootstrapConfiguration") {
    group = "verification"
    doLast {
        check(hasSigning && rootProject.file(signing.getProperty("storeFile", "missing-keystore")).isFile) {
            "Configure your private upload keystore in signing.properties before the first internal-track bundle."
        }
        check(setting("privacyUrl").startsWith("https://")) { "Set the hosted HTTPS privacyUrl in publisher.properties." }
    }
}
tasks.configureEach {
    if (name == "bundleRelease" || name == "assembleRelease") dependsOn("verifyPublishingConfiguration")
    if (name == "bundleBootstrap" || name == "assembleBootstrap") dependsOn("verifyBootstrapConfiguration")
}
