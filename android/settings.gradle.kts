// LUMEN — Mobile Operator Agent
// Spec version 1.0. See AGENTS.md for the binding contract.

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "lumen"

// --- pure Kotlin/JVM core (no Android plugin, no android.* on the classpath) ---
include(":core:model")
include(":core:action")
include(":core:grounding")
include(":core:policy")
include(":core:quota")
include(":core:sandbox")
include(":core:store")
include(":core:brain")
include(":core:protocol")
include(":core:agent")

// --- Android platform adapters ---
include(":platform:accessibility")
include(":platform:capture")
include(":platform:virtualdisplay")
include(":platform:idle")
include(":platform:overlay")
include(":platform:inference")

// --- application modules ---
include(":agent")
include(":app")
