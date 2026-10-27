plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.1.0"
    id("com.gradleup.shadow") version "9.6.1"
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
        // PlaceholderAPI (soft dependency, compile only)
        maven("https://repo.extendedclip.com/releases/") {
            content { includeGroup("me.clip") }
        }
        // VaultUnlocked v2 economy API (compile only)
        maven("https://repo.codemc.org/repository/maven-public/") {
            content { includeGroup("net.milkbowl.vault") }
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
    // Resolved at runtime by JustAuctionLoader (libraries.txt), not shaded
    compileOnly("com.zaxxer:HikariCP:${property("hikariVersion")}")
    compileOnly("me.clip:placeholderapi:${property("placeholderApiVersion")}")
    compileOnly("net.milkbowl.vault:VaultUnlockedAPI:${property("vaultUnlockedApiVersion")}")
    // The only library that is shaded (relocated, as bStats requires); everything else is resolved at runtime
    implementation("org.bstats:bstats-bukkit:${property("bstatsVersion")}")

    testImplementation("io.papermc.paper:paper-api:${property("paperApiVersion")}")
    testImplementation("com.github.MilkBowl:VaultAPI:1.7.1")
    testImplementation("org.xerial:sqlite-jdbc:${property("sqliteVersion")}")
    testImplementation("com.zaxxer:HikariCP:${property("hikariVersion")}")
    testImplementation("org.slf4j:slf4j-simple:2.0.17")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED") // sqlite-jdbc loads a native library
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version,
        "apiVersion" to project.property("apiVersion"),
        "sqliteVersion" to project.property("sqliteVersion"),
        "mariadbVersion" to project.property("mariadbVersion"),
        "hikariVersion" to project.property("hikariVersion"),
        "bstatsId" to project.property("bstatsId"),
    )
    inputs.properties(props)
    filesMatching(listOf("paper-plugin.yml", "libraries.txt", "build.properties")) { expand(props) }
}

// The shaded jar is the release artifact: same file name as before, plain jar task switched off
tasks.jar { enabled = false }
tasks.shadowJar {
    archiveClassifier = ""
    relocate("org.bstats", "me.skerik.justauction.libs.bstats")
    exclude("META-INF/maven/**", "META-INF/*.md")
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
