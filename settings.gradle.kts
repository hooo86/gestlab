pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "GestlabERP"

include(":app")

// Moteur OpenVPN (ics-openvpn, GPLv2), récupéré par scripts/setup-vpnlib.sh.
include(":vpnlib")
project(":vpnlib").projectDir = file("vendor/ics-openvpn/main")
