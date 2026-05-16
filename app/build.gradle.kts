plugins {
    alias(libs.plugins.android.application)
}

import java.util.Properties
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

val appVersionCode = 7
val appVersionName = "1.0.6"

fun buildConfigString(value: String): String {
    val v = value.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$v\""
}

fun localProperty(name: String): String? {
    val f = rootProject.file("local.properties")
    if (!f.exists()) return null
    return try {
        val props = Properties()
        f.inputStream().use { props.load(it) }
        props.getProperty(name)?.trim()?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }
}

val supabaseUrl = providers.gradleProperty("SUPABASE_URL")
    .orElse(localProperty("SUPABASE_URL") ?: "")
    .get()
val supabaseAnonKey = providers.gradleProperty("SUPABASE_ANON_KEY")
    .orElse(localProperty("SUPABASE_ANON_KEY") ?: "")
    .get()
val modelLatestJsonUrl = providers.gradleProperty("MODEL_LATEST_JSON_URL")
    .orElse("https://raw.githubusercontent.com/wdxyp/K2C-Translator-greedy-app/main/latest.json")
    .get()
val appLatestJsonUrl = providers.gradleProperty("APP_LATEST_JSON_URL")
    .orElse("https://raw.githubusercontent.com/wdxyp/K2C-Translator-greedy-app/main/app_latest.json")
    .get()

val appLatestApkUrl = providers.gradleProperty("APP_LATEST_APK_URL")
    .orElse(
        localProperty("APP_LATEST_APK_URL")
            ?: "https://github.com/wdxyp/K2C-Translator-greedy-app/releases/latest/download/app-arm64-v8a-release.apk"
    )
    .get()

val appLatestNotes = providers.gradleProperty("APP_LATEST_NOTES")
    .orElse(localProperty("APP_LATEST_NOTES") ?: "V$appVersionName")
    .get()

android {
    namespace = "com.example.k2ctranslator"
    compileSdk = 35
    buildToolsVersion = "36.0.0"

    androidResources {
        noCompress += "ptl"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    defaultConfig {
        applicationId = "com.example.k2ctranslator"
        minSdk = 24
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BUILD_DATE", "\"2026-05-14\"")
        buildConfigField("String", "SUPABASE_URL", buildConfigString(supabaseUrl))
        buildConfigField("String", "SUPABASE_ANON_KEY", buildConfigString(supabaseAnonKey))
        buildConfigField("String", "SUPABASE_STORAGE_BUCKET", "\"k2c-models\"")
        buildConfigField("String", "MODEL_LATEST_JSON_URL", buildConfigString(modelLatestJsonUrl))
        buildConfigField("String", "APP_LATEST_JSON_URL", buildConfigString(appLatestJsonUrl))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

val releaseAppName = providers.gradleProperty("RELEASE_APP_NAME")
    .orElse(localProperty("RELEASE_APP_NAME") ?: "K2CTranslator")
    .get()

val archiveArm64ReleaseApk = tasks.register("archiveArm64ReleaseApk") {
    group = "release"
    description = "Archives the single arm64-v8a release APK with a timestamped name."

    doLast {
        fun looksLikeArm64ReleaseApk(f: java.io.File): Boolean {
            val n = f.name.lowercase()
            return f.isFile &&
                n.endsWith(".apk") &&
                n.contains("arm64-v8a") &&
                n.contains("release")
        }

        val commonDirs = listOf(
            layout.buildDirectory.dir("outputs/apk/release").get().asFile,
            layout.buildDirectory.dir("intermediates/apk/release").get().asFile,
            project.layout.projectDirectory.dir("release").asFile,
        )

        val apksInCommonDirs = commonDirs
            .filter { it.exists() }
            .flatMap { dir -> dir.listFiles()?.filter(::looksLikeArm64ReleaseApk).orEmpty() }

        val apks = if (apksInCommonDirs.isNotEmpty()) {
            apksInCommonDirs
        } else {
            layout.buildDirectory.asFile.get()
                .walkTopDown()
                .maxDepth(6)
                .filter(::looksLikeArm64ReleaseApk)
                .toList()
        }

        check(apks.isNotEmpty()) {
            val scanned = commonDirs.joinToString { it.absolutePath }
            "No arm64-v8a release APK found. Checked: $scanned (and build/ recursively as fallback)"
        }

        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val dstDir = rootProject.layout.projectDirectory.dir("release_apks").asFile.apply { mkdirs() }
        val dst = File(dstDir, "${releaseAppName}_${appVersionName}_${ts}.apk")

        apks.maxBy { it.lastModified() }.copyTo(dst, overwrite = false)
        println("Archived: ${dst.absolutePath}")
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "packageRelease" }.configureEach {
    if (name == "assembleRelease") {
        outputs.upToDateWhen { false }
    }
    finalizedBy(archiveArm64ReleaseApk)
}

val updateAppLatestJson = tasks.register("updateAppLatestJson") {
    group = "release"
    description = "Updates root app_latest.json from Gradle version values."

    doLast {
        val f = rootProject.file("app_latest.json")
        val json = """
            {
              "versionCode": $appVersionCode,
              "versionName": "$appVersionName",
              "apkUrl": "${appLatestApkUrl.replace("\\", "\\\\").replace("\"", "\\\"")}",
              "notes": "${appLatestNotes.replace("\\", "\\\\").replace("\"", "\\\"")}"
            }
        """.trimIndent() + "\n"
        f.writeText(json, Charsets.UTF_8)
        println("Updated: ${f.absolutePath}")
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "packageRelease" }.configureEach {
    finalizedBy(updateAppLatestJson)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("org.pytorch:pytorch_android_lite:1.13.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
