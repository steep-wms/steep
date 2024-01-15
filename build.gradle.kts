import java.text.SimpleDateFormat
import java.util.Date
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    application
    jacoco
    kotlin("jvm") version "1.9.21"
    id("com.github.ben-manes.versions") version "0.50.0"
    id("se.patrikerdes.use-latest-versions") version "0.2.18"
}

buildscript {
    repositories {
        mavenCentral()
    }
}

group = "de.fhg.igd"
version = "6.7.1"

val prometheusClientVersion by extra("0.16.0")
val slf4jVersion by extra("2.0.9")
val logbackVersion by extra("1.4.11")
val junitVersion by extra("5.10.0")
val testcontainersVersion by extra("1.19.0")

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.slf4j:jul-to-slf4j:$slf4jVersion")
    implementation("org.slf4j:log4j-over-slf4j:$slf4jVersion")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")

    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("ch.qos.logback:logback-core:$logbackVersion")
    implementation("ch.qos.logback.contrib:logback-jackson:0.1.5")
    implementation("ch.qos.logback.contrib:logback-json-classic:0.1.5")
    implementation("org.codehaus.janino:janino:3.1.11") // for conditionals in logback.xml

    implementation("io.vertx:vertx-core:4.5.0")
    implementation("io.vertx:vertx-hazelcast:4.5.0")
    implementation("io.vertx:vertx-lang-kotlin:4.5.0")
    implementation("io.vertx:vertx-lang-kotlin-coroutines:4.5.0")
    implementation("io.vertx:vertx-micrometer-metrics:4.5.0")
    implementation("io.vertx:vertx-mongo-client:4.5.0")
    implementation("io.vertx:vertx-pg-client:4.5.0")
    implementation("io.vertx:vertx-web:4.5.0")
    implementation("io.vertx:vertx-web-client:4.5.0")

    implementation("commons-codec:commons-codec:1.16.0")
    implementation("commons-io:commons-io:2.15.1")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.16.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0")
    implementation("com.github.luben:zstd-jni:1.5.5-11")
    implementation("com.github.zafarkhaja:java-semver:0.9.0")
    implementation("com.google.guava:guava:32.1.3-jre")
    implementation("com.ongres.scram:client:2.1") // support PostgreSQL SCRAM-SHA-256 authentication
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("io.airlift:aircompressor:0.25")
    implementation("io.pebbletemplates:pebble:3.2.2")
    implementation("io.projectreactor:reactor-core:3.6.0") // necessary for reactive MongoDB driver
    implementation("io.prometheus:simpleclient:$prometheusClientVersion")
    implementation("io.micrometer:micrometer-registry-prometheus:1.12.0")
    implementation("org.apache.ant:ant:1.10.14")
    implementation("org.apache.commons:commons-lang3:3.14.0")
    implementation("org.apache.commons:commons-text:1.11.0")
    implementation("org.flywaydb:flyway-core:10.5.0")
    implementation("org.mongodb:mongodb-driver-reactivestreams:4.11.1")
    implementation("com.github.openstack4j.core:openstack4j:3.11")
    implementation("org.parboiled:parboiled-java:1.4.1")
    implementation("org.postgresql:postgresql:42.7.1")
    implementation("org.quartz-scheduler:quartz:2.3.2") {
        // we only need org.quartz.CronExpression, so we can exclude all dependencies
        isTransitive = false
    }
    implementation("org.yaml:snakeyaml:2.2")

    implementation(kotlin("reflect"))
    implementation(kotlin("scripting-jvm"))
    implementation(kotlin("scripting-jvm-host"))
    implementation(kotlin("stdlib"))
    implementation(kotlin("script-runtime"))

    testImplementation("de.flapdoodle.embed:de.flapdoodle.embed.mongo:4.11.1")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("io.vertx:vertx-junit5:4.5.0")
    testImplementation("org.assertj:assertj-core:3.24.2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-params:$junitVersion")
    testImplementation("org.testcontainers:testcontainers:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
}

application {
    mainClass.set("MainKt")

    applicationDefaultJvmArgs = listOf(
        // required to improve performance of Hazelcast
        "--add-modules", "java.se",
        "--add-exports", "java.base/jdk.internal.ref=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.nio=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens", "java.management/sun.management=ALL-UNNAMED",
        "--add-opens", "jdk.management/com.sun.management.internal=ALL-UNNAMED"
    )
}

jacoco {
    toolVersion = "0.8.7"
}

tasks {
    fun isNonStable(version: String): Boolean {
        val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.uppercase().contains(it) }
        val regex = "^[0-9,.v-]+(-r)?$".toRegex()
        val isStable = stableKeyword || regex.matches(version)
        return !isStable
    }

    withType<DependencyUpdatesTask> {
        rejectVersionIf {
            isNonStable(candidate.version) && !isNonStable(currentVersion)
        }
    }

    test {
        useJUnitPlatform()
    }

    jacocoTestReport {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    kotlin {
        target {
            compilations {
                val main by getting

                create("plugins") {
                    dependencies {
                        implementation(main.compileDependencyFiles + main.output.classesDirs)
                    }

                    defaultSourceSet {
                        kotlin.srcDir("$projectDir/conf/plugins")
                    }
                }
            }

            compilations.all {
                kotlinOptions {
                    jvmTarget = "11"
                }
            }
        }
    }

    compileKotlin {
        sourceSets {
            main {
                resources {
                    srcDirs("$buildDir/generated-src/main/resources")
                    srcDirs("$projectDir/ui/out")
                }
            }
        }
    }

    withType<Test>().configureEach {
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).takeIf { it > 0 } ?: 1
    }

    val generateVersionFile by creating {
        doLast {
            val dst = File(buildDir, "generated-src/main/resources")
            dst.mkdirs()
            val versionFile = File(dst, "version.json")
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
            val timestamp = format.format(Date())
            val versionText = """{
              |  "version": "$version",
              |  "build": "${System.getenv("GITHUB_RUN_NUMBER") ?: ""}",
              |  "commit": "${System.getenv("GITHUB_SHA") ?: ""}",
              |  "timestamp": "$timestamp"
              |}""".trimMargin()
            versionFile.writeText(versionText)
        }
    }

    processResources {
        dependsOn(generateVersionFile)
        dependsOn(":ui:processResources")
    }

    // customize start scripts
    startScripts {
        // customize application name
        applicationName = "steep"

        // change current directory to APP_HOME
        doLast {
            val windowsScriptFile = file(getWindowsScript())
            val unixScriptFile = file(getUnixScript())
            windowsScriptFile.writeText(windowsScriptFile.readText()
                .replace("@rem Execute steep".toRegex(), "$0\r\ncd \"%APP_HOME%\""))
            unixScriptFile.writeText(unixScriptFile.readText()
                .replaceFirst("\nexec.+".toRegex(), "\ncd \"\\\$APP_HOME\"$0"))
        }
    }

    val compilePluginsKotlin by getting
    jar {
        dependsOn(compilePluginsKotlin)
    }

    distributions {
        main {
            contents {
                // include 'conf' directory in distribution
                from(projectDir) {
                    include("conf/**/*")
                }
                from("$buildDir/classes/kotlin/plugins") {
                    include("*.class")
                    into("conf/plugins")
                }
            }
        }
    }
}
