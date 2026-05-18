plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// `git describe` output baked into the APK at build time. On a tagged build
// this is just the tag (e.g. "v0.1"); on a development commit it carries the
// nearest tag, distance, short SHA, and a `-dirty` suffix when the working
// tree has uncommitted changes. Uses providers.exec (not ProcessBuilder) so
// the configuration cache treats it as a tracked input rather than an
// untracked external process.
val gitDescribe: String = providers.exec {
    commandLine("git", "describe", "--tags", "--dirty", "--always", "--abbrev=7")
    workingDir = rootDir
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }.getOrElse("").ifEmpty { "dev" }

android {
    namespace = "no.fyhn.uvindex"
    compileSdk = 35

    defaultConfig {
        applicationId = "no.fyhn.uvindex"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        buildConfigField("String", "GIT_DESCRIBE", "\"$gitDescribe\"")
    }

    // Signing config for release builds reads from env vars so the keystore
    // path and passwords stay out of the repo. The keystore file itself lives
    // outside the working tree (see README). Debug builds keep using the
    // auto-generated debug keystore — this only affects release.
    signingConfigs {
        create("release") {
            providers.environmentVariable("SUNBURN_KEYSTORE").orNull
                ?.let { storeFile = file(it) }
            storePassword = providers.environmentVariable("SUNBURN_KEYSTORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("SUNBURN_KEY_ALIAS").orElse("sunburn").get()
            keyPassword = providers.environmentVariable("SUNBURN_KEY_PASSWORD").orNull
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true  // needed for the GIT_DESCRIBE constant above
    }

    // Don't run lint during normal debug builds; invoke `./gradlew lint` explicitly when needed.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// Name the release APK after the app + git-describe version, e.g.
// sunburn-v0.1.apk on a tagged build or sunburn-v0.1-3-gabc1234-dirty.apk
// off-tag — much friendlier than the default app-release.apk when sideloading.
// Uses the legacy applicationVariants API because outputFileName isn't yet
// exposed on the modern variant.outputs in AGP 8.7.
android.applicationVariants.all {
    if (buildType.name == "release") {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "sunburn-$gitDescribe.apk"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
