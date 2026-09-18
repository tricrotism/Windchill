plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    alias(libs.plugins.runPaper)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

val agent: SourceSet by sourceSets.creating

dependencies {
    compileOnly(libs.paper.api)
    implementation(libs.bundles.cloud)
}

kotlin {
    jvmToolchain(25)
}

tasks {
    val agentJar by registering(Jar::class) {
        archiveFileName = "windchill-agent.jar"
        destinationDirectory = layout.buildDirectory.dir("agent")
        from(agent.output)
        manifest {
            attributes(
                "Agent-Class" to "com.tricrotism.windchill.agent.WindchillAgent",
                "Premain-Class" to "com.tricrotism.windchill.agent.WindchillAgent",
                "Can-Retransform-Classes" to "true",
                "Can-Redefine-Classes" to "true",
            )
        }
    }

    named<JavaCompile>("compileAgentJava") {
        options.release = 25
    }

    processResources {
        from(agentJar)

        val props = mapOf("version" to version)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }

    shadowJar {
        archiveClassifier = ""
        relocate("org.incendo.cloud", "com.tricrotism.windchill.libs.cloud")
        relocate("io.leangen.geantyref", "com.tricrotism.windchill.libs.geantyref")
        mergeServiceFiles()
    }

    build {
        dependsOn(shadowJar)
    }

    runServer {
        minecraftVersion(libs.versions.minecraft.get())
        jvmArgs(
            "-Xms2G", "-Xmx2G",
            "-Djdk.attach.allowAttachSelf=true",
            "-XX:+EnableDynamicAgentLoading",
            "--add-opens", "jdk.attach/sun.tools.attach=ALL-UNNAMED",
        )

        when (providers.gradleProperty("windchillAot").orNull) {
            "record" -> jvmArgs("-XX:AOTCacheOutput=plugins/Windchill/windchill.aot")
            "use" -> jvmArgs("-XX:AOTCache=plugins/Windchill/windchill.aot", "-XX:AOTMode=auto")
        }
    }
}
