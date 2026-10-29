// BuildReplay: zeichnet relevante Bauaktivität pro SimpleCloud-Netzwerksitzung mit AdvancedReplay auf.
// Laufzeitbibliotheken (HikariCP, MariaDB-JDBC, Jedis) lädt Paper über "libraries:" in der plugin.yml,
// AdvancedReplay, ProtocolLib und die SimpleCloud-API stellt der Server bereit (compileOnly, nie shaden).
plugins {
    id("java-library")
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

description = "Netzwerkweite Bau-Replays pro SimpleCloud-Sitzung auf Basis von AdvancedReplay"

val paperVersion = "1.21.11-R0.1-SNAPSHOT"
// Muss zur deployten simplecloud-api (cloud-api-paper.jar) passen - Klassen identisch (javap/md5 geprüft).
val simpleCloudApiVersion = "0.1.0-platform.60-dev.7.1-36c9669"
val protocolLibVersion = "5.4.0"
// Diese drei Versionen landen per processResources auch in der plugin.yml ("libraries:").
val hikariVersion = "7.1.0"
val mariadbVersion = "3.5.10"
val jedisVersion = "7.5.3"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.dmulloy2.net/repository/public/")
    maven("https://repo.simplecloud.app/snapshots")
}

dependencies {
    // Reihenfolge wichtig: paper-api vor dem SimpleCloud-Fat-Jar (enthält eigene Adventure-Klassen).
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
    compileOnly("app.simplecloud.api:api:$simpleCloudApiVersion") { isTransitive = false }
    compileOnly("net.dmulloy2:ProtocolLib:$protocolLibVersion") { isTransitive = false }
    // AdvancedReplay 1.8.19 (Commit dfebae7) ist auf keinem Maven-Repo mit den relocateten Klassen verfügbar.
    compileOnly(files("libs/AdvancedReplay-1.8.19.jar"))

    compileOnly("com.zaxxer:HikariCP:$hikariVersion")
    compileOnly("org.mariadb.jdbc:mariadb-java-client:$mariadbVersion")
    compileOnly("redis.clients:jedis:$jedisVersion")

    testImplementation("io.papermc.paper:paper-api:$paperVersion")
    testImplementation("net.dmulloy2:ProtocolLib:$protocolLibVersion") { isTransitive = false }
    testImplementation(files("libs/AdvancedReplay-1.8.19.jar"))
    testImplementation("com.zaxxer:HikariCP:$hikariVersion")
    testImplementation("org.mariadb.jdbc:mariadb-java-client:$mariadbVersion")
    testImplementation("redis.clients:jedis:$jedisVersion")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("ch.vorburger.mariaDB4j:mariaDB4j:3.3.1")
    testImplementation("com.github.codemonstur:embedded-redis:1.4.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xmaxerrs", "1000"))
    }

    test {
        useJUnitPlatform()
        // eingebettete MariaDB/Redis starten echte Prozesse - Test-JVMs nacheinander
        maxParallelForks = 1
        systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
        systemProperty("buildreplay.test.tmp", layout.buildDirectory.dir("test-tmp").get().asFile.absolutePath)
        testLogging {
            events("failed", "skipped")
            showStandardStreams = false
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    runServer {
        minecraftVersion("1.21.11")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf(
            "version" to project.version,
            "description" to project.description,
            "hikari" to hikariVersion,
            "mariadb" to mariadbVersion,
            "jedis" to jedisVersion,
        )
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    jar {
        archiveBaseName.set("BuildReplay")
    }
}
