import java.net.URI
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The Spicetify repository root (this Android project lives in <repo>/android).
val repoRoot: File = rootProject.projectDir.parentFile
val generatedAssets = layout.buildDirectory.dir("generated/spicetify-assets")
val engineAar = file("libs/spicengine.aar")

// Marketplace release to bundle. "latest" or a tag such as "v1.0.8".
val marketplaceVersion = (findProperty("spicetify.marketplaceVersion") as String?) ?: "latest"
val ndkVersionName = "27.2.12479018"

android {
    namespace = "app.spicetify.android"
    compileSdk = 36
    ndkVersion = ndkVersionName

    defaultConfig {
        applicationId = "app.spicetify.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Release builds are signed with the key from these properties, or
        // fall back to the debug key so that a release APK can always be built.
        create("release") {
            val storeFilePath = findProperty("spicetify.storeFile") as String?
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = findProperty("spicetify.storePassword") as String?
                keyAlias = findProperty("spicetify.keyAlias") as String?
                keyPassword = findProperty("spicetify.keyPassword") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig =
                if (findProperty("spicetify.storeFile") != null) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(generatedAssets)
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(files(engineAar))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.webkit)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}

// ---------------------------------------------------------------------------
// Spicetify engine (Go, via gomobile)
// ---------------------------------------------------------------------------

val buildEngine by tasks.registering(Exec::class) {
    description = "Builds libs/spicengine.aar from the Go patch engine with gomobile."
    group = "spicetify"
    val goSources = listOf("src/patcher", "src/preprocess", "src/apply", "src/utils", "android/engine")
    goSources.forEach { inputs.dir(File(repoRoot, it)) }
    inputs.file(File(repoRoot, "go.mod"))
    outputs.file(engineAar)
    onlyIf { findProperty("spicetify.skipEngineBuild") == null || !engineAar.exists() }

    workingDir = File(repoRoot, "android/engine")
    val gomobile = System.getenv("GOMOBILE") ?: File(System.getProperty("user.home"), "go/bin/gomobile")
        .takeIf { it.exists() }?.absolutePath ?: "gomobile"
    commandLine(
        gomobile, "bind",
        "-target=android/arm64,android/arm,android/amd64",
        "-androidapi", "26",
        "-javapkg", "app.spicetify.android",
        "-trimpath",
        // 16 KB page alignment is required on newer devices (Android 15+).
        "-ldflags", "-s -w -extldflags=-Wl,-z,max-page-size=16384",
        "-o", engineAar.absolutePath,
        ".",
    )
    doFirst {
        val sdk = android.sdkDirectory
        environment("ANDROID_HOME", sdk.absolutePath)
        environment("ANDROID_NDK_HOME", File(sdk, "ndk/$ndkVersionName").absolutePath)
        engineAar.parentFile.mkdirs()
    }
}

// ---------------------------------------------------------------------------
// Bundled Spicetify files (assets/spicetify)
// ---------------------------------------------------------------------------

val buildWrapper by tasks.registering(Exec::class) {
    description = "Builds jsHelper/spicetifyWrapper.js (pnpm build:wrapper)."
    group = "spicetify"
    inputs.dir(File(repoRoot, "src/jsHelper/spicetifyWrapper"))
    outputs.file(File(repoRoot, "jsHelper/spicetifyWrapper.js"))
    workingDir = repoRoot
    commandLine("pnpm", "build:wrapper")
}

val marketplaceDir = layout.buildDirectory.dir("marketplace/$marketplaceVersion")

val fetchMarketplace by tasks.registering {
    description = "Downloads the Spicetify Marketplace release."
    group = "spicetify"
    val outDir = marketplaceDir
    outputs.dir(outDir)
    onlyIf { findProperty("spicetify.skipMarketplace") == null }
    doLast {
        val target = outDir.get().asFile
        if (File(target, "index.js").exists()) return@doLast
        val url = if (marketplaceVersion == "latest") {
            "https://github.com/spicetify/marketplace/releases/latest/download/marketplace.zip"
        } else {
            "https://github.com/spicetify/marketplace/releases/download/$marketplaceVersion/marketplace.zip"
        }
        target.mkdirs()
        ZipInputStream(URI(url).toURL().openStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                // The archive contains a single top-level folder (marketplace-dist/).
                val name = entry.name.substringAfter('/', "")
                if (entry.isDirectory || name.isEmpty() || name.contains("..")) continue
                val out = File(target, name)
                out.parentFile.mkdirs()
                out.outputStream().use { zip.copyTo(it) }
            }
        }
    }
}

val prepareSpicetifyAssets by tasks.registering(Sync::class) {
    description = "Collects Spicetify files into assets/spicetify."
    group = "spicetify"
    dependsOn(buildWrapper, fetchMarketplace)
    into(generatedAssets.map { it.dir("spicetify") })
    from(File(repoRoot, "jsHelper")) {
        into("jsHelper")
        include("*.js")
    }
    from(File(repoRoot, "Extensions")) { into("Extensions") }
    from(File(repoRoot, "CustomApps")) { into("CustomApps") }
    from(File(repoRoot, "Themes")) { into("Themes") }
    from(File(repoRoot, "android/web")) { into("android") }
    from(marketplaceDir) { into("CustomApps/marketplace") }
    from(repoRoot) {
        include("css-map.json")
        // Extension metadata (names, descriptions) for the manager UI.
        include("manifest.json")
        rename("manifest.json", "extensions-manifest.json")
    }
}

tasks.named("preBuild") {
    dependsOn(prepareSpicetifyAssets, buildEngine)
}
