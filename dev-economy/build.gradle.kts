// DEV ONLY: in-memory Vault economy for testing. Never shipped.
dependencies {
    compileOnly("io.papermc.paper:paper-api:${rootProject.property("paperApiVersion")}")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    compileOnly("net.milkbowl.vault:VaultUnlockedAPI:${rootProject.property("vaultUnlockedApiVersion")}")
}

tasks.processResources {
    val props = mapOf("version" to project.version, "apiVersion" to project.property("apiVersion"))
    inputs.properties(props)
    filesMatching("paper-plugin.yml") { expand(props) }
}
