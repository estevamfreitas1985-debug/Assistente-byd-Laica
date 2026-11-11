plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val vCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

// Modelo de fala em português (Vosk) para a palavra de chamada, baixado na hora de compilar
val voskAssetsDir = layout.buildDirectory.dir("generated/vosk-assets").get().asFile
val downloadVoskModel = tasks.register("downloadVoskModel") {
    outputs.dir(voskAssetsDir)
    doLast {
        val modelDir = File(voskAssetsDir, "vosk-pt")
        if (File(modelDir, "am/final.mdl").exists()) return@doLast
        val zip = File(layout.buildDirectory.get().asFile, "vosk-model-small-pt.zip")
        if (!zip.exists()) {
            zip.parentFile.mkdirs()
            uri("https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip").toURL().openStream().use { input ->
                zip.outputStream().use { output -> input.copyTo(output) }
            }
        }
        project.copy {
            from(project.zipTree(zip))
            into(modelDir)
            includeEmptyDirs = false
            eachFile {
                relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray())
            }
        }
    }
}

android {
    namespace = "com.assistenteseal.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.assistenteseal.app"
        minSdk = 26
        // 28 de propósito: menos restrições de segundo plano na central do carro
        targetSdk = 28
        versionCode = vCode
        versionName = "0.1.$vCode"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(voskAssetsDir)
        }
    }

    signingConfigs {
        create("release") {
            // a chave vem do segredo KEYSTORE_B64 do GitHub (ou é gerada na hora pelo workflow)
            storeFile = file("release.p12")
            // sem o segredo, o GitHub manda a senha vazia (não nula): nesse caso vale a senha da chave temporária
            val senha = System.getenv("KEYSTORE_PASS")?.takeIf { it.isNotBlank() } ?: "assistente"
            storePassword = senha
            keyAlias = "laica"
            keyPassword = senha
            storeType = "pkcs12"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/INDEX.LIST", "META-INF/versions/**", "META-INF/*.kotlin_module"
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn(downloadVoskModel)
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("dev.mobile:dadb:1.2.10")
}
