import com.github.jengelman.gradle.plugins.shadow.transformers.PreserveFirstFoundResourceTransformer

plugins {
    kotlin("jvm") version "2.4.20-Beta1"
    id("com.gradleup.shadow") version "9.6.0"
}

val claimoApiVersion = "1.3"
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
        // Transformers below need to see every copy of a duplicated path, not just the first one.
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        mergeServiceFiles()

        // Several dependencies ship META-INF/LICENSE + META-INF/NOTICE. Duplicate jar entries make
        // Paper's plugin remapper (1.20.5 - 1.21.x) abort with "Duplicate entries detected", so keep
        // the first copy of each and drop the rest. Done with a transformer rather than
        // DuplicatesStrategy.EXCLUDE, which would also starve mergeServiceFiles() of its input.
        transform(PreserveFirstFoundResourceTransformer::class.java) {
            include("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*LICENSE*")
        }

        manifest {
            attributes("paperweight-mappings-namespace" to "mojang")
        }

        exclude("org/slf4j/**")
        // Build/packaging metadata that has no runtime meaning inside a plugin jar.
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

        // Drop classes nothing reaches - trove4j, commons-collections4 and kotlin-stdlib ship
        // thousands of classes of which JDA and our own code touch a small fraction.
        // Jackson is the one library here that resolves types reflectively, so it is kept whole;
        // excluding anything else would cascade to its whole subtree (trove and commons-collections4
        // are children of JDA, kotlin-stdlib is a child of okhttp) and disable minimization.
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
