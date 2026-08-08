@file:Suppress("UNUSED_VARIABLE")

import com.android.build.gradle.AppExtension
import com.android.build.gradle.BaseExtension
import java.net.URL
import java.util.*

buildscript {
    repositories {
        mavenCentral()
        google()
        maven("https://raw.githubusercontent.com/MetaCubeX/maven-backup/main/releases")
    }
    dependencies {
        classpath(libs.build.android)
        classpath(libs.build.kotlin.common)
        classpath(libs.build.kotlin.serialization)
        classpath(libs.build.kotlin.compose)
        classpath(libs.build.ksp)
        classpath(libs.build.golang)
    }
}

subprojects {
    repositories {
        mavenCentral()
        google()
        maven("https://raw.githubusercontent.com/MetaCubeX/maven-backup/main/releases")
    }

    val isApp = name == "app"

    apply(plugin = if (isApp) "com.android.application" else "com.android.library")

    fun queryConfigProperty(key: String): Any? {
        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localProperties.load(localPropertiesFile.inputStream())
        } else {
            return null
        }
        return localProperties.getProperty(key)
    }

    extensions.configure<BaseExtension> {
        buildFeatures.buildConfig = true
        defaultConfig {
            // Moshen core version, read from go.mod at build time (the
            // `replace ... => github.com/legiz-ru/moshen vX.Y.Z` line).
            val moshenCoreVersion = rootProject.file("core/src/main/golang/go.mod")
                .takeIf { it.exists() }?.readText()
                ?.let { Regex("""legiz-ru/moshen\s+(v[\w.\-]+)""").find(it)?.groupValues?.get(1) }
                ?: "unknown"
            buildConfigField("String", "CORE_VERSION", "\"$moshenCoreVersion\"")

            if (isApp) {
                val customApplicationId = queryConfigProperty("custom.application.id") as? String?
                applicationId = customApplicationId.takeIf { it?.isNotBlank() == true } ?: "ru.legiz.prizrakbox"

                // Ship only Russian, English and (Simplified) Chinese; strips
                // every other locale pulled in by AndroidX/Material libraries.
                resourceConfigurations += listOf("en", "ru", "zh")
            }

            project.name.let { name ->
                namespace = if (name == "app") "com.github.kr328.clash"
                else "com.github.kr328.clash.$name"
            }

            minSdk = 21
            targetSdk = 35

            versionName = "1.3.0"
            versionCode = 103000

            resValue("string", "release_name", "v$versionName")
            resValue("integer", "release_code", "$versionCode")

            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            }

            externalNativeBuild {
                cmake {
                    abiFilters("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                }
            }

            if (!isApp) {
                consumerProguardFiles("consumer-rules.pro")
            } else {
                setProperty("archivesBaseName", "prizrak-box-$versionName")
            }
        }

        ndkVersion = "29.0.14206865"

        compileSdkVersion(defaultConfig.targetSdk!!)

        if (isApp) {
            packagingOptions {
                resources {
                    excludes.add("DebugProbesKt.bin")
                }
            }
        }

        productFlavors {
            flavorDimensions("feature")

            val removeSuffix = (queryConfigProperty("remove.suffix") as? String)?.toBoolean() == true

            create("alpha") {
                isDefault = true
                dimension = flavorDimensionList[0]
                if (!removeSuffix) {
                    versionNameSuffix = ".Alpha"
                }


                buildConfigField("boolean", "PREMIUM", "Boolean.parseBoolean(\"false\")")

                // Канал обновлений — свойство сборки, а не вывод из applicationId
                // или имени версии: remove.suffix стирает оба суффикса, и такой
                // вывод развалился бы молча.
                buildConfigField("String", "UPDATE_CHANNEL", "\"alpha\"")

                resValue("string", "launch_name", "@string/launch_name_alpha")
                resValue("string", "application_name", "@string/application_name_alpha")

                if (isApp && !removeSuffix) {
                    applicationIdSuffix = ".alpha"
                }

                // Код версии alpha-сборок приезжает из workflow пререлиза
                // (100000 + номер прогона) через local.properties: тег и имя
                // версии у alpha постоянны, и сравнивать внутри канала больше
                // нечего. Вне CI свойства нет — остаётся код версии из
                // defaultConfig.
                if (isApp) {
                    (queryConfigProperty("alpha.version.code") as? String)
                        ?.trim()?.toIntOrNull()
                        ?.let { versionCode = it }
                }
            }

            create("meta") {

                dimension = flavorDimensionList[0]
                if (!removeSuffix) {
                    versionNameSuffix = ".Meta"
                }

                buildConfigField("boolean", "PREMIUM", "Boolean.parseBoolean(\"false\")")

                // См. комментарий в alpha-флейворе: канал задаётся явно.
                buildConfigField("String", "UPDATE_CHANNEL", "\"meta\"")

                resValue("string", "launch_name", "@string/launch_name_meta")
                resValue("string", "application_name", "@string/application_name_meta")

                if (isApp && !removeSuffix) {
                    applicationIdSuffix = ".meta"
                }
            }
        }

        sourceSets {
            getByName("meta") {
                java.srcDirs("src/foss/java")
            }
            getByName("alpha") {
                java.srcDirs("src/foss/java")
            }
        }

        signingConfigs {
            val keystore = rootProject.file("signing.properties")
            if (keystore.exists()) {
                create("release") {
                    val prop = Properties().apply {
                        keystore.inputStream().use(this::load)
                    }

                    storeFile = rootProject.file("release.keystore")
                    storePassword = prop.getProperty("keystore.password")!!
                    keyAlias = prop.getProperty("key.alias")!!
                    keyPassword = prop.getProperty("key.password")!!
                }
            }
        }

        buildTypes {
            named("release") {
                isMinifyEnabled = isApp
                isShrinkResources = isApp
                signingConfig = signingConfigs.findByName("release") ?: signingConfigs["debug"]
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            }
            named("debug") {
                versionNameSuffix = ".debug"
            }
        }

        if (isApp) {
            this as AppExtension

            splits {
                abi {
                    isEnable = true
                    isUniversalApk = true
                    reset()
                    include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                }
            }
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
    }
}

task("clean", type = Delete::class) {
    delete(rootProject.buildDir)
}

tasks.wrapper {
    distributionType = Wrapper.DistributionType.ALL

    doLast {
        val sha256 = URL("$distributionUrl.sha256").openStream()
            .use { it.reader().readText().trim() }

        file("gradle/wrapper/gradle-wrapper.properties")
            .appendText("distributionSha256Sum=$sha256")
    }
}