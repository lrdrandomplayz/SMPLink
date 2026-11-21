plugins {
    java
    id("com.gradleup.shadow") version "8.3.6"
}

group = "io.github.lrdrandomplayz"
version = "0.1.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.playpro.com")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    // Optional at runtime: /check raid reads its block and container logs when it is installed.
    compileOnly("net.coreprotect:coreprotect:24.1")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("net.dv8tion:JDA:6.7.0") {
        // Voice support is unused, and Paper already ships slf4j.
        exclude(module = "opus-java")
        exclude(group = "com.google.crypto.tink")
        exclude(group = "org.slf4j")
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    processResources {
        // Without this Gradle reuses the old plugin.yml after a version bump.
        inputs.property("version", project.version)
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }

    test {
        useJUnitPlatform()
    }

    // Only the shaded jar is useful on a server; skipping the plain jar keeps build/libs unambiguous.
    jar {
        enabled = false
    }

    shadowJar {
        archiveFileName.set("SMPLink.jar")
        // sqlite-jdbc finds its native library by package path, so it must not be relocated.
        mergeServiceFiles()
        // JDA and its libraries are relocated so they can't clash with other plugins that bundle
        // their own copies (DiscordSRV ships JDA too).
        val libs = "io.github.lrdrandomplayz.smplink.libs"
        relocate("net.dv8tion.jda", "$libs.jda")
        relocate("okhttp3", "$libs.okhttp3")
        relocate("okio", "$libs.okio")
        relocate("kotlin", "$libs.kotlin")
        relocate("com.neovisionaries", "$libs.neovisionaries")
        relocate("org.apache.commons.collections4", "$libs.collections4")
        relocate("gnu.trove", "$libs.trove")
        relocate("com.fasterxml.jackson", "$libs.jackson")
        relocate("org.jetbrains.annotations", "$libs.jetbrains.annotations")
        relocate("org.intellij.lang.annotations", "$libs.intellij.annotations")
    }

    build {
        dependsOn(shadowJar)
    }
}
