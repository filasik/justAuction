plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

allprojects {
    group = "me.skerik"
    version = property("pluginVersion") as String

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        // Vault v1 API (net.milkbowl.vault.economy.Economy); works with Vault and VaultUnlocked
        maven("https://jitpack.io") {
            content { includeGroup("com.github.MilkBowl") }
        }
    }
}

subprojects {
    apply(plugin = "java")
}

allprojects {
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion = JavaLanguageVersion.of(25)
        }
        tasks.withType<JavaCompile> {
            options.encoding = "UTF-8"
            options.release = 25
            // Warnings are errors: no deprecated API without an explicit, commented suppression
            options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
        }
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${property("paperApiVersion")}")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val props = mapOf("version" to project.version, "apiVersion" to project.property("apiVersion"))
    inputs.properties(props)
    filesMatching("paper-plugin.yml") { expand(props) }
}

tasks.runServer {
    minecraftVersion(project.property("minecraftVersion") as String)
    // Override with -PrunDir=<path> to reuse an existing test server (its eula.txt, world, other plugins)
    (findProperty("runDir") as String?)?.let { runDirectory = file(it) }
    // VaultUnlocked is a drop-in Vault replacement; the economy itself comes from :dev-economy
    downloadPlugins {
        url("https://github.com/TheNewEconomy/VaultUnlocked/releases/download/2.20.1/VaultUnlocked-2.20.1.jar")
    }
    pluginJars(project(":dev-economy").tasks.named<Jar>("jar").flatMap { it.archiveFile })
}
