import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing key lives outside version control; see keystore.properties.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// On-device speech recognition (sherpa-onnx). The official AAR isn't published to Maven, so the
// build fetches it from sherpa-onnx's GitHub release and checks it against a pinned checksum.
val sherpaVersion = "1.13.8"
val sherpaAar = file("libs/sherpa-onnx-static-link-onnxruntime-$sherpaVersion.aar")
val sherpaSha256 = "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471"

fun sha256(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

val fetchSherpaOnnx by tasks.registering {
    description = "Downloads the sherpa-onnx Android library if it isn't in app/libs yet."
    outputs.file(sherpaAar)
    doLast {
        if (sherpaAar.exists() && sha256(sherpaAar) == sherpaSha256) return@doLast
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/${sherpaAar.name}"
        logger.lifecycle("Downloading $url")
        sherpaAar.parentFile.mkdirs()
        val part = File(sherpaAar.path + ".part")
        URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        val actual = sha256(part)
        check(actual == sherpaSha256) { "Checksum mismatch for ${sherpaAar.name}: got $actual" }
        check(part.renameTo(sherpaAar)) { "Couldn't move ${part.name} into place" }
    }
}

android {
    namespace = "com.kjwindham.audiocool"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kjwindham.audiocool"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "2.4"
        // The speech engine is native code; ship only the 64-bit ARM build every current phone uses.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            if (!keystoreProps.isEmpty) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (keystoreProps.isEmpty) "debug" else "release")
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

    lint {
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Compressing the native library keeps the APK download about 15 MB smaller.
        jniLibs.useLegacyPackaging = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.named("preBuild") { dependsOn(fetchSherpaOnnx) }

// Optional: -PsherpaHostDir=<dir> runs the real speech engine in unit tests on Linux x86_64. The dir
// holds lib/ (sherpa-onnx's linux-x64 JNI libraries), models/ and silero_vad.onnx; without it, those
// tests are skipped.
tasks.withType<Test>().configureEach {
    providers.gradleProperty("sherpaHostDir").orNull?.let { dir ->
        systemProperty("sherpa.host.dir", dir)
        systemProperty("java.library.path", "$dir/lib")
    }
    // Model comparison (ModelBenchmarkHostTest): -PasrBenchDir=<test set> -PasrBenchModels=type:dir,...
    providers.gradleProperty("asrBenchDir").orNull?.let { systemProperty("asr.bench.dir", it) }
    // Speaker models for "who said what" (DiarizationBenchHostTest): -PdiarBenchDir, -PdiarModels, -PdiarThresholds, -PdiarSeg.
    providers.gradleProperty("diarBenchDir").orNull?.let { systemProperty("diar.bench.dir", it) }
    providers.gradleProperty("diarModels").orNull?.let { systemProperty("diar.models", it) }
    providers.gradleProperty("diarThresholds").orNull?.let { systemProperty("diar.thresholds", it) }
    providers.gradleProperty("diarSeg").orNull?.let { systemProperty("diar.seg", it) }
    providers.gradleProperty("diarSame").orNull?.let { systemProperty("diar.same", it) }
    providers.gradleProperty("diarSimilarities").orNull?.let { systemProperty("diar.similarities", it) }
    providers.gradleProperty("diarRecognition").orNull?.let { systemProperty("diar.recognition", it) }
    providers.gradleProperty("diarChunk").orNull?.let { systemProperty("diar.chunk", it) }
    providers.gradleProperty("asrBenchModels").orNull?.let { systemProperty("asr.bench.models", it) }
    providers.gradleProperty("asrBenchThreads").orNull?.let { systemProperty("asr.bench.threads", it) }
    providers.gradleProperty("asrBenchLeveling").orNull?.let { systemProperty("asr.bench.leveling", it) }
    providers.gradleProperty("asrBenchMaxSegment").orNull?.let { systemProperty("asr.bench.maxSegment", it) }
    providers.gradleProperty("asrBenchDenoiser").orNull?.let { systemProperty("asr.bench.denoiser", it) }
    // Desktop sync against a running AudioCool Desktop (DesktopEndToEndHostTest):
    // -PdesktopUrl=http://localhost:8765 -PdesktopToken=<pairing code>
    providers.gradleProperty("desktopUrl").orNull?.let { systemProperty("desktop.url", it) }
    providers.gradleProperty("desktopToken").orNull?.let { systemProperty("desktop.token", it) }
    // The README's screenshots (ReadmeScreenshots): -PreadmeScreenshots=docs/screenshots
    providers.gradleProperty("readmeScreenshots").orNull?.let { systemProperty("readme.screenshots", rootProject.file(it).path) }
    systemProperty("desktop.clip", rootProject.file("desktop/tests/data/jfk.m4a").path)
    maxHeapSize = "4g"
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // The lock screen's camera (1.6 needs compileSdk 36; move up with the Android 16 target).
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    // Summaries on the phone: runs a downloaded Gemma model (CPU, or the GPU through OpenCL).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.1")
    // Reads the text in photos of slides; the model is bundled, so it works offline.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Scans the desktop's pairing QR code with Google's scanner (no camera permission needed).
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(files(sherpaAar))

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in local unit tests; use the real one.
    testImplementation("org.json:json:20240303")
    // UI flow tests run the real app on the JVM under Robolectric.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // Hosts single composables (createAndroidComposeRule<ComponentActivity>) in tests.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // A fake AudioCool Desktop for testing the phone's client.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
