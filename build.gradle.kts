import java.text.SimpleDateFormat
import java.util.Date
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.script.tryConstructClassFromStringArgs

plugins {
    application
    jacoco
    kotlin("jvm") version "1.3.11"
}

group = "de.fhg.igd"
version = "3.0.0-SNAPSHOT"

val vertxVersion by extra("3.6.2")

repositories {
    jcenter()

    // necessary for jacoco 0.8.3-SNAPSHOT
    maven("https://oss.sonatype.org/content/repositories/snapshots")
}

dependencies {
    implementation("org.slf4j:jul-to-slf4j:1.7.21")
    implementation("org.slf4j:log4j-over-slf4j:1.7.21")
    implementation("org.slf4j:slf4j-api:1.7.21")

    implementation("ch.qos.logback:logback-classic:1.1.7")
    implementation("ch.qos.logback:logback-core:1.1.7")

    implementation("io.vertx:vertx-core:$vertxVersion")
    implementation("io.vertx:vertx-hazelcast:$vertxVersion")
    implementation("io.vertx:vertx-jdbc-client:$vertxVersion")
    implementation("io.vertx:vertx-lang-kotlin:$vertxVersion")
    implementation("io.vertx:vertx-lang-kotlin-coroutines:$vertxVersion")
    implementation("io.vertx:vertx-mongo-client:$vertxVersion")
    implementation("io.vertx:vertx-web:$vertxVersion")

    implementation("commons-codec:commons-codec:1.11")
    implementation("commons-io:commons-io:2.6")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.9.8")
    implementation("com.google.guava:guava:27.0.1-jre")
    implementation("org.apache.commons:commons-lang3:3.8.1")
    implementation("org.flywaydb:flyway-core:5.2.4")
    implementation("org.postgresql:postgresql:42.2.5")
    implementation("org.yaml:snakeyaml:1.23")

    implementation(kotlin("reflect"))
    implementation(kotlin("stdlib-jdk8"))

    testImplementation("io.mockk:mockk:1.8.13.kotlin13")
    testImplementation("io.vertx:vertx-junit5:$vertxVersion")
    testImplementation("org.assertj:assertj-core:3.11.1")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.3.2")
    testImplementation("org.testcontainers:testcontainers:1.10.5")
    testImplementation("org.testcontainers:postgresql:1.10.5")
}

application {
    mainClassName = "MainKt"
}

jacoco {
    toolVersion = "0.8.3-SNAPSHOT"
}

tasks {
    test {
        useJUnitPlatform()
    }

    compileKotlin {
        kotlinOptions {
            jvmTarget = "1.8"
        }
        sourceSets {
            main {
                resources {
                    srcDirs("$buildDir/generated-src/main/resources")
                }
            }
        }
    }

    compileTestKotlin {
        kotlinOptions {
            jvmTarget = "1.8"
        }
    }

    val generateVersionFile = register("generateVersionFile") {
        doLast {
            val dst = File(buildDir, "generated-src/main/resources")
            dst.mkdirs()
            val versionFile = File(dst, "version.json")
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
            val timestamp = format.format(Date())
            val versionText = """{
              |  "version": "$version",
              |  "build": "${System.getenv("CI_PIPELINE_IID") ?: ""}",
              |  "commit": "${System.getenv("CI_COMMIT_SHA") ?: ""}",
              |  "timestamp": "${timestamp}"
              |}""".trimMargin()
            versionFile.writeText(versionText)
        }
    }

    processResources {
        dependsOn(generateVersionFile)
    }
}
