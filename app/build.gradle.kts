import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.github.vying.osc"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "com.github.vying.osc"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 46
        versionName = "1.6"
        multiDexEnabled = true
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    packaging {
        // 压缩 native so（对齐官方 release 包，显著减小体积）
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/beans.xml",
                "META-INF/*.kotlin_module", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/ASL2.0", "DebugProbesKt.bin",
            )
        }
    }

    androidResources {
        localeFilters += listOf("en", "zh", "zh-rCN", "b+zh+Hant", "zh-rTW", "zh-rHK")
    }

    sourceSets {
        getByName("main") {
            java.directories += "src/python/java"
        }
    }

    signingConfigs {
        val storeFilePath = project.findProperty("RELEASE_STORE_FILE") as String? ?: ".key/app-release.jks"
        val storeFileResolved = rootProject.file(storeFilePath)
        if (storeFileResolved.exists()) {
            create("release") {
                storeFile = storeFileResolved
                storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as String
                keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String
                keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as String
            }
        }
    }

    buildTypes {
        debug {
            // 关闭 debuggable 让 R8 完整执行优化与混淆，显著减小包体并提升首页滑动流畅度。
            // 仍使用 debug 签名，可直接覆盖安装。
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "proguard-python.pro",
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro", "proguard-python.pro")
        }
    }
    splits {
        abi {
            isEnable = false
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("AVBox_${variant.buildType}.apk")
        }
    }
}

dependencies {
    api(fileTree("libs") { include("*.jar", "*.aar") })

    implementation(libs.nanohttpd)
    implementation(libs.cling.core)
    implementation(libs.cling.support)
    compileOnly(libs.cdi.api)
    compileOnly(libs.javax.inject)
    compileOnly(libs.javax.annotation.api)
    compileOnly(libs.javax.servlet.api)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.media)
    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.okio)
    implementation(libs.gson)
    implementation(libs.autosize)
    implementation(libs.xstream) {
        exclude(group = "xmlpull", module = "xmlpull")
        exclude(group = "xpp3", module = "xpp3_min")
    }
    implementation(libs.mmkv)
    implementation(libs.danmaku.flame.master)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.datasource)
    implementation(libs.media3.datasource.rtmp)
    implementation(libs.media3.database)
    implementation(libs.media3.ui)
    implementation(libs.media3.ffmpeg.decoder)
    implementation(libs.media3.effect)
    implementation(project(":quickjs"))
    implementation(project(":pyramid"))

    implementation(libs.xx.permissions)
    implementation(libs.jsoup)
    implementation(libs.commons.io)
    implementation(libs.juniversalchardet)
    implementation(libs.zxing.core)
    implementation(libs.sardine) {
        exclude(group = "xpp3", module = "xpp3")
    }

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.materialkolor)
    implementation(project(":libs:backdrop"))
    implementation(libs.capsule)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}

configurations.configureEach {
    exclude(group = "io.antmedia", module = "rtmp-client")
}
