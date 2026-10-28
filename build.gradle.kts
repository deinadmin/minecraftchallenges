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
