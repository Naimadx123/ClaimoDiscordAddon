plugins {
    kotlin("jvm") version "2.4.20-Beta1"
    id("com.gradleup.shadow") version "9.6.0"
}

val claimoApiVersion = "1.3-SNAPSHOT"
val jdaVersion = "6.5.0"
val hikariVersion = "6.3.0"
val relocateBase = "zone.vao.claimoDiscordAddon.libs"

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.vao.zone/releases")
    maven("https://repo.vao.zone/snapshots")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("zone.vao:claimo-api:$claimoApiVersion")
    compileOnly("com.zaxxer:HikariCP:$hikariVersion")
    compileOnly("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("net.dv8tion:JDA:$jdaVersion") {
        exclude(module = "opus-java")
        exclude(module = "tink")
    }
}

kotlin {
    jvmToolchain(21)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    jar {
        enabled = false
    }

    shadowJar {
        archiveFileName.set("ClaimoDiscordAddon-v${project.version}.jar")
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        mergeServiceFiles()
        exclude("org/slf4j/**")
        dependencies {
            exclude(dependency("org.slf4j:.*:.*"))
        }
        listOf(
            "net.dv8tion.jda",
            "com.fasterxml.jackson",
            "okhttp3",
            "okio",
            "com.neovisionaries",
            "gnu.trove",
            "org.apache.commons.collections4",
            "com.github.benmanes.caffeine",
        ).forEach { pkg ->
            relocate(pkg, "$relocateBase.${pkg.substringAfterLast('.')}")
        }
    }

    processResources {
        val props = mapOf(
            "version" to version,
            "hikariVersion" to hikariVersion,
        )
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
