import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    alias(libs.plugins.kotlin.serialization)
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val jojoHomeVersionProperties = Properties().apply {
    val file = rootProject.file("jojo-home-version.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val jojoHomeVersion = providers.gradleProperty("jojoHomeVersion").orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: jojoHomeVersionProperties.getProperty("versionName", "0.0.1")
val jojoHomeVersionCode = providers.gradleProperty("jojoHomeVersionCode").orNull
    ?.toIntOrNull()
    ?: jojoHomeVersionProperties.getProperty("versionCode", "1").toIntOrNull()
    ?: 1
val visualCrossingApiKey = localProperties.getProperty("visualCrossingApiKey", "")
val tmdbApiKey = localProperties.getProperty("TMDB_API_KEY", "")
val requestedApkAbi = providers.gradleProperty("jojoHomeAbi").orNull
    ?.trim()
    ?.takeIf { it in setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }

android {
    namespace = "com.jojotv.home"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    defaultConfig {
        applicationId = "com.jojotv.home"
        minSdk = 24
        targetSdk = 36
        versionCode = jojoHomeVersionCode
        versionName = jojoHomeVersion
        resourceConfigurations += listOf("fr")
        buildConfigField("String", "VISUAL_CROSSING_API_KEY", "\"$visualCrossingApiKey\"")
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
        buildConfigField("String", "INTRODB_API_URL", "\"https://api.introdb.app/\"")
        buildConfigField("String", "UNIQUE_CONTRIBUTIONS_BASE_URL", "\"\"")
        buildConfigField("String", "DONATIONS_BASE_URL", "\"\"")
        buildConfigField("String", "TRAILER_API_URL", "\"\"")
        buildConfigField("String", "IMDB_RATINGS_API_BASE_URL", "\"\"")
        buildConfigField("String", "IMDB_TAPFRAME_API_BASE_URL", "\"\"")
        buildConfigField("String", "JOJOTV_NAS_SYNC_URL", "\"${localProperties.getProperty("JOJOTV_NAS_SYNC_URL", "http://192.168.1.32:8095")}\"")
        buildConfigField("boolean", "DOVI_NATIVE_ENABLED", "false")
        buildConfigField("boolean", "DOVI_EXTRACTOR_HOOK_READY", "false")
        buildConfigField("boolean", "JOJO_PROGRESS_PILOT_COMPILED",
            providers.gradleProperty("jojoProgressPilotCompiled").orNull?.toBooleanStrictOrNull()?.toString() ?: "false")
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        create("quick") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    splits {
        abi {
            isEnable = !gradle.startParameter.taskNames.any { it.contains("debug", ignoreCase = true) }
            reset()
            if (requestedApkAbi != null) {
                include(requestedApkAbi)
                isUniversalApk = false
            } else {
                include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
                isUniversalApk = true
            }
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "org/bouncycastle/pqc/crypto/picnic/lowmcL1.bin.properties",
                "org/bouncycastle/pqc/crypto/picnic/lowmcL3.bin.properties",
                "org/bouncycastle/pqc/crypto/picnic/lowmcL5.bin.properties"
            )
        }
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf(
                "lib/*/libc++_shared.so",
                "lib/*/libavcodec.so",
                "lib/*/libavdevice.so",
                "lib/*/libavfilter.so",
                "lib/*/libavformat.so",
                "lib/*/libavutil.so",
                "lib/*/libswscale.so",
                "lib/*/libswresample.so"
            )
        }
    }
}

// Keep the legacy host-only screens out of Kotlin/KSP source discovery. The
// compile-task filters below are not enough: Hilt/KSP still aggregates their
// annotations and then fails when those intentionally isolated classes are
// absent from javac.
kotlin {
    sourceSets {
        getByName("main") {
            kotlin.exclude("com/nuvio/tv/core/auth/**")
            kotlin.exclude("com/nuvio/tv/core/recommendations/**")
            kotlin.exclude("com/nuvio/tv/core/server/**")
            kotlin.exclude("com/nuvio/tv/core/sync/**")
            kotlin.exclude("com/nuvio/tv/ui/screens/home/**")
            kotlin.exclude("com/nuvio/tv/ui/screens/detail/**")
            kotlin.exclude("com/nuvio/tv/ui/screens/iptv/**")
            // The IPTV avatar catalog and images are hosted by the NAS.
            kotlin.exclude("com/nuvio/tv/ui/components/**")
            kotlin.exclude("com/nuvio/tv/ui/util/**")
            kotlin.exclude("com/nuvio/tv/data/repository/**")
            kotlin.exclude("com/nuvio/tv/data/local/CollectionsDataStore.kt")
            kotlin.exclude("com/nuvio/tv/data/remote/nas/**")
            kotlin.exclude("com/nuvio/tv/data/freebox/FreeboxFrameThumbnailService.kt")
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    exclude("com/nuvio/tv/core/recommendations/**")
    exclude("com/nuvio/tv/core/server/**")
    exclude("com/nuvio/tv/core/sync/**")
    exclude("com/nuvio/tv/ui/screens/home/**")
    exclude("com/nuvio/tv/ui/screens/detail/**")
    exclude("com/nuvio/tv/ui/screens/iptv/**")
    exclude("com/nuvio/tv/ui/components/**")
    exclude("com/nuvio/tv/ui/util/**")
    exclude("com/nuvio/tv/data/repository/**")
    exclude("com/nuvio/tv/data/local/CollectionsDataStore.kt")
    exclude("com/nuvio/tv/data/remote/nas/**")
    exclude("com/nuvio/tv/core/auth/AuthManager.kt")
    exclude("com/nuvio/tv/data/freebox/FreeboxFrameThumbnailService.kt")
    exclude("com/nuvio/tv/ui/util/DrawerFocusRequesters.kt")
}

tasks.withType<JavaCompile>().configureEach {
    exclude("com/nuvio/tv/core/auth/**")
    exclude("com/nuvio/tv/core/recommendations/**")
    exclude("com/nuvio/tv/core/server/**")
    exclude("com/nuvio/tv/core/sync/**")
    doFirst {
        source = source.asFileTree.matching {
            exclude("com/nuvio/tv/core/auth/**")
            exclude("com/nuvio/tv/core/recommendations/**")
            exclude("com/nuvio/tv/core/server/**")
            exclude("com/nuvio/tv/core/sync/**")
            exclude("com/nuvio/tv/ui/screens/home/**")
            exclude("com/nuvio/tv/ui/screens/detail/**")
            exclude("com/nuvio/tv/ui/screens/iptv/**")
            exclude("com/nuvio/tv/data/repository/**")
            exclude("com/nuvio/tv/data/freebox/FreeboxFrameThumbnailService_*")
            exclude("com/nuvio/tv/data/local/CollectionsDataStore_*")
            exclude("com/nuvio/tv/data/remote/nas/**")
            exclude("com/nuvio/tv/ui/components/**")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
    implementation("org.slf4j:slf4j-api:2.0.7")
    // jcifs-ng uses Bouncy Castle for NTLM/MD4 on Android, where the platform
    // provider no longer exposes MD4.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation(platform("androidx.compose:compose-bom:2026.01.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.tv:tv-foundation:1.0.0-rc01")
    implementation("androidx.tv:tv-material:1.1.0-rc01")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // DNS presets/custom resolvers: bounded UDP/TCP queries, CNAME handling and TTL cache.
    implementation("dnsjava:dnsjava:3.6.4")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.9.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("io.coil-kt.coil3:coil-gif:3.3.0")
    implementation("io.coil-kt.coil3:coil-svg:3.3.0")
    implementation("com.google.code.gson:gson:2.10.1")
    // Local Companion receiver and QR setup for the native IPTV module.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")
    compileOnly("org.checkerframework:checker-qual:3.43.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.dagger:hilt-android:2.59.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")
    ksp("com.google.dagger:hilt-android-compiler:2.59.2")
    ksp(libs.moshi.codegen)
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")
    implementation("androidx.media3:media3-datasource:1.8.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.8.0")
    implementation("androidx.media3:media3-decoder:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.media3:media3-common:1.8.0")
    implementation("androidx.media3:media3-container:1.8.0")
    implementation("androidx.media3:media3-extractor:1.8.0")
    implementation(files("libs/lib-exoplayer-release.aar"))
    implementation(files("libs/lib-ui-release.aar"))
    implementation(files("libs/lib-decoder-ffmpeg-release.aar"))
    implementation(files("libs/nextlib-mediainfo-local.aar"))
    implementation("io.github.peerless2012:ass-media:0.4.0-beta01")
    testImplementation("junit:junit:4.13.2")
}

configurations.configureEach {
    exclude(group = "androidx.media3", module = "media3-exoplayer")
    exclude(group = "androidx.media3", module = "media3-ui")
}

// Hilt's aggregate task can still retain metadata emitted by legacy host-only
// sources even when those sources are excluded from javac. Remove only those
// entries from the generated component tree before Hilt compiles it. This is a
// build artifact cleanup; no source or runtime player dependency is removed.
tasks.matching {
    it.name == "hiltAggregateDepsDebug" ||
        it.name == "hiltAggregateDepsQuick" ||
        it.name == "hiltAggregateDepsRelease"
}.configureEach {
    val variantName = name.removePrefix("hiltAggregateDeps")
        .replaceFirstChar { it.lowercase() }
    doLast {
        val excludedAggregatedDeps = listOf(
            "_com_nuvio_tv_core_auth_",
            "_com_nuvio_tv_core_recommendations_",
            "_com_nuvio_tv_core_server_",
            "_com_nuvio_tv_core_sync_",
            "_com_nuvio_tv_data_repository_",
            "_com_nuvio_tv_data_freebox_",
            "_com_nuvio_tv_data_local_CollectionsDataStore",
            "_com_nuvio_tv_data_remote_nas_",
            "_com_nuvio_tv_ui_components_",
            "_com_nuvio_tv_ui_screens_home_",
            "_com_nuvio_tv_ui_screens_detail_",
            "_com_nuvio_tv_ui_screens_iptv_"
        )
        fileTree("$buildDir/generated/hilt/component_trees/$variantName")
            .matching { include("**/*_ComponentTreeDeps.java") }
            .files
            .forEach { componentTree ->
                val filtered = componentTree.readLines()
                    .filterNot { line -> excludedAggregatedDeps.any(line::contains) }
                    .joinToString(System.lineSeparator()) + System.lineSeparator()
                componentTree.writeText(filtered)
            }
    }
}
