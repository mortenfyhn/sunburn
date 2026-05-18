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
