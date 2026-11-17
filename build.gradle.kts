plugins {
    java
}

group = "de.carl"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    compileOnly("io.netty:netty-transport:4.2.16.Final")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.jar {
    archiveFileName.set("MinecraftChallenges.jar")
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

fun installPluginInTestServer() {
    val pluginsDirectory = layout.projectDirectory.dir("server/plugins").asFile
    pluginsDirectory.mkdirs()

    // Entfernt ausschließlich alte Dateien dieses Plugins, nicht fremde Plugins.
    delete(fileTree(pluginsDirectory) {
        include("MinecraftChallenges.jar")
        include("minecraft-challenges-*.jar")
    })
    copy {
        from(tasks.jar.flatMap { it.archiveFile })
        into(pluginsDirectory)
    }
}

tasks.build {
    // doLast wird nur erreicht, wenn der komplette Build erfolgreich war.
    doLast {
        installPluginInTestServer()
        logger.lifecycle("Plugin installiert: server/plugins/MinecraftChallenges.jar")
    }
}

tasks.register("deployPlugin") {
    group = "development"
    description = "Baut das Plugin und kopiert es nach server/plugins."
    dependsOn(tasks.build)
}

// Executable policy checks use the API compile classpath; no test framework or server required.
val npcTest = sourceSets.create("npcTest") {
    compileClasspath += sourceSets.main.get().output + configurations.compileClasspath.get()
    runtimeClasspath += output + compileClasspath
}
val npcPolicyTest = tasks.register<JavaExec>("npcPolicyTest") {
    group = "verification"
    description = "Checks NPC Quick Join selection, capacity, and custom slot availability."
    dependsOn(npcTest.classesTaskName)
    classpath = npcTest.runtimeClasspath
    mainClass.set("de.carl.minecraftchallenges.npc.QuickJoinTest")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
tasks.check { dependsOn(npcPolicyTest) }

val chatFormatTest = tasks.register<JavaExec>("chatFormatTest") {
    group = "verification"
    description = "Checks chat prefixes, duplicate prevention, ranks and component preservation."
    dependsOn(npcTest.classesTaskName)
    classpath = npcTest.runtimeClasspath
    mainClass.set("de.carl.minecraftchallenges.chat.ChatFormatTest")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
tasks.check { dependsOn(chatFormatTest) }

val npcLookTest = tasks.register<JavaExec>("npcLookTest") {
    group = "verification"
    description = "Checks per-viewer NPC yaw and pitch, vertical targets and independent viewers."
    dependsOn(npcTest.classesTaskName)
    classpath = npcTest.runtimeClasspath
    mainClass.set("de.carl.minecraftchallenges.npc.NpcLookDirectionTest")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
tasks.check { dependsOn(npcLookTest) }

val challengeHudTest = tasks.register<JavaExec>("challengeHudTest") {
    group = "verification"
    description = "Checks per-player challenge HUD visibility and updates across round changes."
    dependsOn(npcTest.classesTaskName)
    classpath = npcTest.runtimeClasspath
    mainClass.set("de.carl.minecraftchallenges.challenge.ChallengeHudTest")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
tasks.check { dependsOn(challengeHudTest) }

val challengeKeepAliveTest = tasks.register<JavaExec>("challengeKeepAliveTest") {
    group = "verification"
    description = "Checks the /keepalive switch and the 30-minute AFK/offline timeout."
    dependsOn(npcTest.classesTaskName)
    classpath = npcTest.runtimeClasspath
    mainClass.set("de.carl.minecraftchallenges.challenge.ChallengeKeepAliveTest")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
tasks.check { dependsOn(challengeKeepAliveTest) }

val performancePolicyTest = tasks.register<JavaExec>("performancePolicyTest") {
    group = "verification"
    description = "Checks world queue ordering, failure/shutdown recovery and unchanged scoreboard updates."
    dependsOn(npcTest.classesTaskName)
    classpath = npcTest.runtimeClasspath
    mainClass.set("de.carl.minecraftchallenges.util.PerformancePolicyTest")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
tasks.check { dependsOn(performancePolicyTest) }

val economyTest = tasks.register<JavaExec>("economyTest") {
    group = "verification"
    description = "Checks the sell market curve, the shipped value list and the daily shop rotation."
    dependsOn(npcTest.classesTaskName)
    classpath = npcTest.runtimeClasspath
    mainClass.set("de.carl.minecraftchallenges.smp.EconomyTest")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
tasks.check { dependsOn(economyTest) }

val claimRulesTest = tasks.register<JavaExec>("claimRulesTest") {
    group = "verification"
    description = "Checks plot sizes, prices, overlap and spawn distance."
    dependsOn(npcTest.classesTaskName)
    classpath = npcTest.runtimeClasspath
    mainClass.set("de.carl.minecraftchallenges.smp.ClaimRulesTest")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
tasks.check { dependsOn(claimRulesTest) }

// Opt-in only: this plugin creates disposable worlds and stops its test server when finished.
tasks.register<Jar>("performanceSmokeJar") {
    group = "verification"
    description = "Builds an integration-test plugin for an isolated disposable Paper server."
    dependsOn(npcTest.classesTaskName)
    archiveFileName.set("PerformanceServerSmoke.jar")
    destinationDirectory.set(layout.buildDirectory.dir("smoke"))
    from(npcTest.output) { include("de/carl/minecraftchallenges/smoke/**") }
    from("src/serverSmoke") { include("plugin.yml") }
}

// Opt-in only, like the performance smoke: boots a disposable server and checks the SMP systems on it.
tasks.register<Jar>("smpSmokeJar") {
    group = "verification"
    description = "Builds the SMP integration-test plugin for an isolated disposable Paper server."
    dependsOn(npcTest.classesTaskName)
    archiveFileName.set("SmpServerSmoke.jar")
    destinationDirectory.set(layout.buildDirectory.dir("smoke"))
    from(npcTest.output) { include("de/carl/minecraftchallenges/smoke/SmpServerSmoke*") }
    from("src/smpSmoke") { include("plugin.yml") }
}

val readyClickTest = tasks.register<JavaExec>("readyClickTest") {
    group = "verification"
    description = "Checks ready toggles against duplicate block-use and off-hand events."
    dependsOn(npcTest.classesTaskName)
    classpath = npcTest.runtimeClasspath
    mainClass.set("de.carl.minecraftchallenges.util.ReadyClickGateTest")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
tasks.check { dependsOn(readyClickTest) }

val deployScriptTest = tasks.register<Exec>("deployScriptTest") {
    group = "verification"
    description = "Checks SFTP replacement/rollback, world locks, checksums and Kinetic API power-state handling."
    workingDir(layout.projectDirectory)
    commandLine("python3", "-m", "unittest", "discover", "-s", "scripts/tests")
}
tasks.check { dependsOn(deployScriptTest) }
