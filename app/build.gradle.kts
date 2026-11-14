import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// 공공데이터포털 인증키는 커밋하지 않도록 local.properties에서 읽는다:  DATA_GO_KR_KEY=발급받은키
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val dataGoKrKey: String = localProps.getProperty("DATA_GO_KR_KEY", "")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.future1.safewalk"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.future1.safewalk"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        buildConfigField("String", "DATA_GO_KR_KEY", "\"${dataGoKrKey.replace("\"", "")}\"")
    }

    buildFeatures {
        buildConfig = true
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
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
}
