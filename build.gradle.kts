import com.inet.lib.less.Less
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    application
    jacoco
    kotlin("jvm") version "1.3.11"
}

buildscript {
    repositories {
        jcenter()
    }

    dependencies {
        classpath("de.inetsoftware:jlessc:1.7")
    }
}

group = "de.fhg.igd"
version = "3.1.0-SNAPSHOT"

val vertxVersion by extra("3.6.2")
val hazelcastVersion by extra("3.11.1")

repositories {
    jcenter()

    // necessary for openstack4j 3.1.1-SNAPSHOT
    maven("https://oss.sonatype.org/content/repositories/snapshots")
}

val assets by configurations.creating
val closureCompiler by configurations.creating

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
    implementation("io.vertx:vertx-shell:$vertxVersion")
    implementation("io.vertx:vertx-web:$vertxVersion")
    implementation("io.vertx:vertx-web-client:$vertxVersion")
    implementation("com.hazelcast:hazelcast:$hazelcastVersion")

    implementation("commons-codec:commons-codec:1.11")
    implementation("commons-io:commons-io:2.6")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.9.8")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.9.8")
    implementation("com.google.guava:guava:27.0.1-jre")
    implementation("io.pebbletemplates:pebble:3.0.7")
    implementation("org.apache.commons:commons-lang3:3.8.1")
    implementation("org.flywaydb:flyway-core:5.2.4")
    implementation("org.pacesys:openstack4j:3.1.1-SNAPSHOT")
    implementation("org.postgresql:postgresql:42.2.5")
    implementation("org.yaml:snakeyaml:1.23")

    assets("org.webjars:highlightjs:9.8.0")
    assets("org.webjars.npm:jquery:3.3.1")
    assets("org.webjars.npm:semantic-ui:2.4.2") {
        isTransitive = false
    }
    assets("org.webjars.npm:sockjs-client:1.3.0") {
        isTransitive = false
    }
    assets("org.webjars.npm:vue:2.6.6")
    assets("org.webjars.npm:vue-moment:4.0.0") {
        isTransitive = false
    }
    assets("org.webjars.npm:vue-timeago:5.0.0") {
        isTransitive = false
    }
    assets("io.vertx:vertx-web:$vertxVersion:client@js")

    closureCompiler("com.google.javascript:closure-compiler:v20190215")

    implementation(kotlin("reflect"))
    implementation(kotlin("stdlib-jdk8"))

    testImplementation("io.mockk:mockk:1.8.13.kotlin13")
    testImplementation("io.vertx:vertx-junit5:$vertxVersion")
    testImplementation("org.assertj:assertj-core:3.11.1")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.4.1")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.4.1")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.4.1")
    testImplementation("org.testcontainers:testcontainers:1.10.5")
    testImplementation("org.testcontainers:postgresql:1.10.5")
}

application {
    mainClassName = "MainKt"
}

jacoco {
    toolVersion = "0.8.3"
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
                    srcDirs("$buildDir/assets")
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

    val generateVersionFile by creating {
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
              |  "timestamp": "$timestamp"
              |}""".trimMargin()
            versionFile.writeText(versionText)
        }
    }

    val minifyJs by creating(JavaExec::class) {
        val inputFile = "$projectDir/src/main/resources/js/index.js"
        val outputFile = "$buildDir/assets/assets/index.js"
        inputs.files(files(inputFile))
        outputs.files(files(outputFile))
        classpath = closureCompiler
        main = "com.google.javascript.jscomp.CommandLineRunner"
        args = listOf("--js_output_file=$outputFile", inputFile)
    }

    val less by creating {
        val inputFile = "$projectDir/src/main/resources/css/index.less"
        val outputFile = "$buildDir/assets/assets/index.css"
        inputs.files(files(inputFile))
        outputs.files(files(outputFile))
        doLast {
            val r = Less.compile(file(inputFile), true)
            file(outputFile).writeText(r)
        }
    }

    val extractAssets by creating(Sync::class) {
        dependsOn(assets)
        from(assets.map { if (it.extension == "js") it else zipTree(it) })
        include(listOf(
            "META-INF/resources/webjars/highlightjs/9.8.0/styles/zenburn.css",
            "META-INF/resources/webjars/highlightjs/9.8.0/highlight.min.js",
            "META-INF/resources/webjars/jquery/3.3.1/dist/jquery.min.js",
            "META-INF/resources/webjars/semantic-ui/2.4.2/dist/semantic.min.css",
            "META-INF/resources/webjars/semantic-ui/2.4.2/dist/semantic.min.js",
            "META-INF/resources/webjars/semantic-ui/2.4.2/dist/themes/default/**/*",
            "META-INF/resources/webjars/sockjs-client/1.3.0/dist/sockjs.min.js",
            "META-INF/resources/webjars/vue/2.6.6/dist/vue.min.js",
            "META-INF/resources/webjars/vue-moment/4.0.0/dist/vue-moment.min.js",
            "META-INF/resources/webjars/vue-timeago/5.0.0/dist/vue-timeago.min.js",
            "vertx-web-$vertxVersion-client.js"
        ))
        into("$buildDir/assets/assets")
        includeEmptyDirs = false
        eachFile {
            path = path.replace("META-INF/resources/webjars", "")
        }
    }

    processResources {
        dependsOn(generateVersionFile)
        dependsOn(extractAssets)
        dependsOn(minifyJs)
        dependsOn(less)
    }
}
