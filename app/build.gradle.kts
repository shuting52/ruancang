plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ruancang.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ruancang.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.0.4"
        vectorDrawables { useSupportLibrary = true }
    }

    // v1.0.4 固定签名：之前 CI 每次都在临时 Runner 上现生成 debug keystore，
    // 每版签名都不一样，Android 认签名不认包名，签名一变就必须先卸载旧版才能装新版。
    // 现把一个固定 debug keystore 随源码提交（keystore/ruancang-debug.keystore，
    // 别名 androiddebugkey，口令 android，和 Android SDK 自带 debug key 同一级别，
    // 本来就不是秘密，作用只是让每一版签名保持一致）。
    // 注意：这只是 debug 签名，正式发布用的 release key 绝不能这样提交，
    // 到时必须走 GitHub Secrets。
    signingConfigs {
        getByName("debug") {
            storeFile = file("../keystore/ruancang-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.10" }
    packaging {
        resources {
            pickFirsts += "META-INF/kotlin-project-structure-metadata.json"
            excludes += "META-INF/*.kotlin_module"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-P",
            "plugin:androidx.compose.compiler.plugins.kotlin:suppressKotlinVersionCompatibilityCheck=true"
        )
    }
}

dependencies {
    constraints {
        implementation("androidx.collection:collection:1.4.4")
        implementation("androidx.collection:collection-ktx:1.4.4")
        implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    }
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // 注意：GitHub 同步故意不引入 OkHttp / Retrofit，
    // 只用系统 HttpURLConnection + org.json，避免新增依赖拉不到、编不过。
}
