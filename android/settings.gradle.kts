// The public build: the agent app, the AIDL contract with Ubiq Core, and the shared policy rules.
// It builds on its own. Nothing here may refer to Ubiq Core's code or any path outside this repo.
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
rootProject.name = "Ubiq"
include(":app")
include(":coreapi")
include(":policy")
