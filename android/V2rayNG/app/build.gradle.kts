import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.jaredsburrows.license")
}

// Из одного репозитория собирается несколько приложений: у каждого свой
// файл brands/<id>.properties. Какой брать — говорит -Pbrand=<id> или
// переменная окружения BRAND; без них собирается бренд по умолчанию.
val brandId: String = (project.findProperty("brand") as String?)
    ?: System.getenv("BRAND")
    ?: "aviavp"

// Здесь файл только читается: ни имени, ни цветов, ни ссылок в коде нет.
fun loadBrandProperties(): Properties {
    val file = rootProject.file("../brands/$brandId.properties")
    if (!file.isFile) {
        throw GradleException(
            "нет бренда '$brandId': ожидался файл brands/$brandId.properties"
        )
    }
    val props = Properties()
    // Читаем как UTF-8: в файле есть комментарии на русском.
    file.reader(Charsets.UTF_8).use { props.load(it) }
    return props
}

val brandProps: Properties = loadBrandProperties()

fun brand(key: String): String = brandProps.getProperty(key)
    ?: throw GradleException("в brand.properties не хватает ключа $key")

// Имя файла сборки. По умолчанию — из названия приложения, но его
// можно закрепить ключом brand.slug: ссылки на apk уже разошлись,
// и переименование приложения не должно их ломать.
val brandSlug: String = (brandProps.getProperty("brand.slug") ?: brand("brand.name"))
    .lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

// Плагин Firebase подключаем только тогда, когда у бренда есть его
// настройки. Иначе сборка второго приложения падала бы на отсутствующем
// google-services.json — а push ему могут быть и не нужны.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    logger.lifecycle("Firebase: настройки найдены, push включены")
} else {
    logger.lifecycle("Firebase: google-services.json нет — push выключены")
}

android {
    namespace = "com.v2ray.ang"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = brand("brand.applicationId")
        minSdk = 24
        targetSdk = 37
        versionCode = brand("brand.versionCode").toInt()
        versionName = brand("brand.versionName")

        // Имя под иконкой. Из res/values*/strings.xml этот ключ удалён
        // скриптом rebrand.sh — иначе ресурс объявлялся бы дважды.
        resValue("string", "app_name", brand("brand.name"))
        // Плитка в шторке и виджет: ключи так же вычищены из strings.xml
        // скриптом rebrand.sh, иначе ресурс объявлялся бы дважды.
        resValue("string", "app_tile_name", brand("brand.tileName"))
        resValue("string", "app_widget_name", brand("brand.tileName"))

        fun brandString(name: String, key: String) =
            buildConfigField("String", name, "\"${brand(key).replace("\"", "\\\"")}\"")

        brandString("BRAND_NAME", "brand.name")
        brandString("BRAND_SUBSCRIPTION_URL", "brand.subscription.url")
        brandString("BRAND_SUBSCRIPTION_NAME", "brand.subscription.name")
        brandString("BRAND_USER_AGENT", "brand.subscription.userAgent")
        brandString("BRAND_URL_SCHEME", "brand.urlScheme")
        brandString("BRAND_BACKEND_URL", "brand.backend.url")
        manifestPlaceholders["brandUrlScheme"] = brand("brand.urlScheme")
        brandString("BRAND_SUPPORT_URL", "brand.support.url")
        brandString("BRAND_SITE_URL", "brand.site.url")
        buildConfigField("long", "BRAND_ACCENT", "0x${brand("brand.color.accent")}L")
        buildConfigField("long", "BRAND_BACKGROUND", "0x${brand("brand.color.background")}L")
        buildConfigField("long", "BRAND_SURFACE", "0x${brand("brand.color.surface")}L")
        buildConfigField("long", "BRAND_CARD", "0x${brand("brand.color.card")}L")

        val abiFilterList = (properties["ABI_FILTERS"] as? String)?.split(';')
        splits {
            abi {
                isEnable = true
                reset()
                if (!abiFilterList.isNullOrEmpty()) {
                    include(*abiFilterList.toTypedArray())
                } else {
                    include(
                        "arm64-v8a",
                        "armeabi-v7a",
                        "x86_64",
                        "x86"
                    )
                }
                isUniversalApk = abiFilterList.isNullOrEmpty()
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    // Исходные сборки различали F-Droid и Play Store. Мы раздаём APK сами,
    // поэтому вариант один и команда сборки — просто assembleRelease.

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // У каждой архитектуры свой versionCode: Android обязан видеть,
    // что сборка под arm64 новее, чем под armeabi, иначе обновление
    // на части устройств не предложится.
    applicationVariants.all {
        val variant = this
        val abiCodes = mapOf(
            "universal" to 0, "armeabi-v7a" to 1, "x86" to 2, "x86_64" to 3, "arm64-v8a" to 4
        )
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
            .forEach { output ->
                val abi = output.getFilter("ABI") ?: "universal"
                output.outputFileName = "${brandSlug}-${variant.versionName}-${abi}.apk"
                abiCodes[abi]?.let { code ->
                    output.versionCodeOverride = variant.versionCode * 10 + code
                }
            }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf(
            "en",
            "zh-rCN",
            "zh-rTW",
            "vi",
            "ru",
            "fa",
            "ar",
            "bn",
            "bqi-rIR"
        )
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
            // Treat Jupiter discovery warnings as failures: JUnit 4 failed on an invalid @Test
            // method, such as one that returns a value, while Jupiter only warns and skips it.
            // The key is @API(status = EXPERIMENTAL) in JUnit 6; after a JUnit upgrade, check
            // that a temporary `@Test fun probe() = 1` still fails the unit test task.
            it.systemProperty("junit.platform.discovery.issue.severity.critical", "WARNING")
        }
    }

}

dependencies {
    // Push-уведомления. Подключаются всегда, а работают только там, где
    // рядом оказался google-services.json: без него Firebase не
    // настроится, и приложение просто обойдётся без уведомлений.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Core Libraries
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // AndroidX Core Libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Compose Libraries
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coil.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Data and Storage Libraries
    implementation(libs.mmkv.static)
    implementation(libs.gson)
    implementation(libs.okhttp)

    // Reactive and Utility Libraries
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // QR Code: CameraX + ZXing
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.compose)
    implementation(libs.core) // zxing core

    // AndroidX Lifecycle and Architecture Components
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // Background Task Libraries
    implementation(libs.work.runtime.ktx)
    implementation(libs.work.multiprocess)

    // Reorderable list
    implementation(libs.reorderable)

    // Testing Libraries
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
