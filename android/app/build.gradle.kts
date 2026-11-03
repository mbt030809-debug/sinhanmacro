import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI 에서 -PappVersionCode=<run_number> 로 주입 (릴리스 태그 v<번호> 와 동일)
val appVersionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.sinhanmacro.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sinhanmacro.app"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = "1.0.$appVersionCode"
        // 앱 실행 시 새 버전을 확인할 GitHub 저장소 (Releases 의 최신 v<번호> 릴리스)
        buildConfigField("String", "UPDATE_REPO", "\"mbt030809-debug/sinhanmacro\"")
    }

    // 업데이트(덮어쓰기 설치)는 서명키가 같아야 가능.
    // 서명키는 저장소에 평문으로 두지 않음: CI 가 KEYSTORE_FILE / KEYSTORE_PASSWORD 환경변수로 전달.
    // (없으면 debug 키로 서명 → 로컬 빌드용. 이 APK 는 릴리스로 게시되지 않음)
    val keystorePath: String? = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "sinhanmacro"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
}
