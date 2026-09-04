rootProject.name = "openrndr-template"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenLocal()
    }
}

// figma-rest lives in this repo and is built from source. Edits to
// figma-rest/src are picked up on the next run, no publishing step.
includeBuild("figma-rest")
