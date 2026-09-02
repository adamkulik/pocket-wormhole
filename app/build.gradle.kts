plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pocketwormhole.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pocketwormhole.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    // XML parsing (engine + slipstream)
    implementation("org.jdom:jdom2:2.0.6.1")
    // jorbis/jogg classes (minus the engine's patched VorbisFile, which is
    // compiled from sources in-tree)
    implementation(files("libs/jorbis-core.jar"))
    // @NotNull/@Nullable annotations used in engine sources
    implementation("org.jetbrains:annotations:24.0.1")
    // Slipstream deps
    implementation("org.slf4j:slf4j-api:1.7.25")
    implementation("org.slf4j:slf4j-jdk14:1.7.25")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.12.7.1")
}
