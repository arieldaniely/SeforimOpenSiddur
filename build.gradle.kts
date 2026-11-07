plugins {
    kotlin("multiplatform") version "2.3.21"
}

group = "io.github.arieldaniely"
version = "0.1.0"

kotlin {
    jvm()
    jvmToolchain(25)
    sourceSets {
        jvmMain {
            kotlin.srcDir("src/main/kotlin")
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
                implementation("io.github.kdroidfilter:kosherkotlin:3.0.5")
            }
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
    }
}
