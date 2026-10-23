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
        // NewPipeExtractor (the open-source YouTube reader used by NewPipe) is published on JitPack.
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "Earful"
include(":app")
