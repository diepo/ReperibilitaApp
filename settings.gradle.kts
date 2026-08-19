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
        // Richiesto da com.microsoft.identity:common (dipendenza transitiva di MSAL) per
        // com.microsoft.device.display:display-mask, non pubblicato su Maven Central/Google.
        maven {
            url = uri("https://pkgs.dev.azure.com/MicrosoftDeviceSDK/DuoSDK-Public/_packaging/Duo-SDK-Feed/maven/v1")
            name = "Duo-SDK-Feed"
        }
    }
}

rootProject.name = "ReperibilitaApp"
include(":app")
