plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.gtaylor.flashback"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.gtaylor.flashback"
        minSdk = 24
        targetSdk = 35
        versionCode = providers.gradleProperty("flashback.versionCode").get().toInt()
        versionName = providers.gradleProperty("flashback.versionName").get()
    }

    // CI passes env vars; locally, put flashback.keystore.* in ~/.gradle/gradle.properties.
    fun signingValue(env: String, prop: String): String? =
        System.getenv(env) ?: providers.gradleProperty(prop).orNull

    val keystorePath = signingValue("KEYSTORE_PATH", "flashback.keystore.path")

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = signingValue("KEYSTORE_PASSWORD", "flashback.keystore.password")
                keyAlias = signingValue("KEY_ALIAS", "flashback.keystore.alias")
                keyPassword = signingValue("KEY_PASSWORD", "flashback.keystore.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

dependencies {
    implementation(libs.hammerhead.karoo.ext)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.timber)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
