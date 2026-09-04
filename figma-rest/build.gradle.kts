plugins {
    // kept in step with the consuming openrndr-wn-sandbox build, which runs this
    // as an included build and therefore imposes its own Gradle version
    kotlin("multiplatform") version "2.2.10"
    kotlin("plugin.serialization") version "2.2.10"
    `maven-publish`
}

group = "org.openrndr"
version = "0.1-SNAPSHOT"

repositories {
    mavenCentral()
}


kotlin {

    jvm {
    }

    // 17 to match the consumer; a higher target makes Gradle reject the
    // variant as incompatible when openrndr-wn-sandbox resolves it
    jvmToolchain(17)

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-test")
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
            }
        }
    }
}