plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.angelminga"
version = "1.11.1"

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
        // En IntelliJ IDEA el XML es un plugin; en WebStorm viene dentro del núcleo (no hay plugin que agregar)
        val productInfo = file(providers.gradleProperty("ideaPath").get() + "/Contents/Resources/product-info.json")
        if (!productInfo.exists() || productInfo.readText().contains("\"com.intellij.xml\"")) bundledPlugin("com.intellij.xml")
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
            sinceBuild = "262"
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
