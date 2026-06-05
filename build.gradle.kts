plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.stocklite"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.json:json:20240303")
}

intellij {
    version.set("2023.3")
    type.set("IC")
    downloadSources.set(false)
    plugins.set(listOf())
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }
    patchPluginXml {
        sinceBuild.set("233")
        untilBuild.set("")
    }
    signPlugin {
        certificateChain.set(providers.fileContents(layout.projectDirectory.file("certificate.crt")).asText)
        privateKey.set(providers.fileContents(layout.projectDirectory.file("private.pem")).asText)
        password.set(providers.provider { "" })
    }
    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
