import com.github.jengelman.gradle.plugins.shadow.transformers.PreserveFirstFoundResourceTransformer

plugins {
    kotlin("jvm") version "2.4.20-Beta1"
    id("com.gradleup.shadow") version "9.6.0"
}

val claimoApiVersion = "1.4.4-SNAPSHOT"
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
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
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
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        mergeServiceFiles()

        transform(PreserveFirstFoundResourceTransformer::class.java) {
            include("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*LICENSE*")
        }

        manifest {
            attributes("paperweight-mappings-namespace" to "mojang")
        }

        exclude("org/slf4j/**")
        exclude("META-INF/maven/**")
        exclude("META-INF/native-image/**")
        exclude("META-INF/rewrite/**")
        exclude("META-INF/proguard/**")
        exclude("META-INF/versions/*/OSGI-INF/**")
        exclude("META-INF/*.kotlin_module")
        exclude("META-INF/DEPENDENCIES")
        exclude("META-INF/INDEX.LIST")
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        exclude("module-info.class", "META-INF/versions/*/module-info.class")
        dependencies {
            exclude(dependency("org.slf4j:.*:.*"))
        }

        minimize {
            exclude(dependency("com.fasterxml.jackson.*:.*:.*"))
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
