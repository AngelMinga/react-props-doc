plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.angelminga"
version = "1.8.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local(providers.gradleProperty("ideaPath"))
        bundledPlugin("JavaScript")
        bundledPlugin("com.intellij.xml")
        bundledPlugin("com.intellij.modules.json")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

kotlin {
    jvmToolchain(25)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "263"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    instrumentCode = false
}

tasks.test {
    // Prueba de humo opcional contra un proyecto real: -PrealProject=/ruta/al/proyecto
    providers.gradleProperty("realProject").orNull?.let { environment("REACT_PROPS_DOC_PROJECT", it) }
    providers.gradleProperty("realMui").orNull?.let { environment("REACT_PROPS_DOC_MUI", it) }
}
