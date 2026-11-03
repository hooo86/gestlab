import java.util.Properties

plugins {
    id("com.android.application")
}

// Adresse d'Odoo, joignable à travers le VPN.
val odooUrl = "http://odoo.lnhc-dz.com:8069"

// Signature : variables d'environnement (GitHub Actions) ou fichier keystore.properties (poste local).
// Les variables évitent qu'un caractère spécial du mot de passe (\ # ! : =) soit mal lu.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
    mapOf(
        "storeFile" to "GESTLAB_STORE_FILE",
        "storePassword" to "GESTLAB_STORE_PASSWORD",
        "keyAlias" to "GESTLAB_KEY_ALIAS",
        "keyPassword" to "GESTLAB_KEY_PASSWORD",
    ).forEach { (key, env) -> System.getenv(env)?.takeIf { it.isNotEmpty() }?.let { setProperty(key, it) } }
}

android {
    namespace = "com.gestlab.com"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.gestlab.com"
        minSdk = 26
        targetSdk = 36
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = "1.0.0"

        buildConfigField("String", "ODOO_URL", "\"$odooUrl\"")

        // Variante du moteur OpenVPN : sans son interface, avec OpenVPN 2 et 3.
        missingDimensionStrategy("implementation", "skeleton")
        missingDimensionStrategy("ovpnimpl", "ovpn23")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            // Pas de R8 : le moteur OpenVPN utilise JNI et la réflexion.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        targetCompatibility = JavaVersion.VERSION_17
        sourceCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // Le moteur OpenVPN 2 est lancé comme exécutable natif : il doit être extrait.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":vpnlib"))

    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
