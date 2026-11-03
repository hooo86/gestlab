/*
 * Fichier de compilation du moteur OpenVPN (ics-openvpn, GPLv2) en tant que
 * BIBLIOTHÈQUE Android. Copié par scripts/setup-vpnlib.sh à la place de
 * vendor/ics-openvpn/main/build.gradle.kts.
 *
 * Repris du fichier d'origine d'ics-openvpn, sans ce qui est propre à une
 * application (signature, numéro de version, interface « ui »).
 */
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskProvider

plugins {
    id("com.android.library")
}

android {
    namespace = "de.blinkt.openvpn"
    compileSdk = 37
    ndkVersion = "30.0.14904198"

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    defaultConfig {
        minSdk = 24
        ndk {
            // 64 bits obligatoire sur Google Play ; x86_64 pour l'émulateur.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = File("${projectDir}/src/main/cpp/CMakeLists.txt")
        }
    }

    sourceSets {
        getByName("main") {
            assets.directories.add("build/ovpnassets")
        }
    }

    flavorDimensions += listOf("implementation", "ovpnimpl")
    productFlavors {
        // « skeleton » : moteur seul, sans l'interface de l'application OpenVPN.
        create("skeleton") {
            dimension = "implementation"
        }
        create("ovpn23") {
            dimension = "ovpnimpl"
            buildConfigField("boolean", "openvpn3", "true")
        }
    }

    compileOptions {
        targetCompatibility = JavaVersion.VERSION_17
        sourceCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

var swigcmd = "swig"
if (file("/opt/homebrew/bin/swig").exists()) swigcmd = "/opt/homebrew/bin/swig"
else if (file("/usr/local/bin/swig").exists()) swigcmd = "/usr/local/bin/swig"

abstract class GenerateSwigTask : Exec() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}

fun registerGenSwigTask(variantName: String, variantDirName: String): TaskProvider<GenerateSwigTask> {
    val baseDir = layout.buildDirectory.dir("generated/source/ovpn3swig/${variantDirName}")

    return tasks.register<GenerateSwigTask>("generateOpenVPN3Swig${variantName}") {
        val genDir = baseDir.get().asFile.resolve("net/openvpn/ovpn3")
        outputDir.set(baseDir)
        doFirst { mkdir(genDir) }
        commandLine(
            listOf(
                swigcmd,
                "-outdir", genDir.absolutePath,
                "-outcurrentdir",
                "-c++",
                "-java",
                "-package", "net.openvpn.ovpn3",
                "-Isrc/main/cpp/openvpn3/client",
                "-Isrc/main/cpp/openvpn3/",
                "-DOPENVPN_PLATFORM_ANDROID",
                "-o", "${genDir}/ovpncli_wrap.cxx",
                "-oh", "${genDir}/ovpncli_wrap.h",
                "src/main/cpp/openvpn3/client/ovpncli.i"
            )
        )
        inputs.files("src/main/cpp/openvpn3/client/ovpncli.i")
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val execTask = registerGenSwigTask(variant.name, variant.name.replace("-", "/"))
        variant.sources.java?.addGeneratedSourceDirectory(execTask, GenerateSwigTask::outputDir)
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.10.0")
    implementation("androidx.core:core:1.19.0")
    implementation("androidx.security:security-crypto:1.1.0")
}
