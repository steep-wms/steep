import com.inet.lib.less.Less
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    application
    jacoco
    kotlin("jvm") version "1.3.30"
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
version = "3.1.9-SNAPSHOT"

val vertxVersion by extra("3.6.2")
val hazelcastVersion by extra("3.11.1")
val graalVMVersion by extra("1.0.0-rc15")
val prometheusClientVersion by extra("0.6.0")

repositories {
    jcenter()
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
    implementation("com.github.zafarkhaja:java-semver:0.9.0")
    implementation("com.google.guava:guava:27.0.1-jre")
    implementation("com.zaxxer:HikariCP:3.3.1")
    implementation("io.pebbletemplates:pebble:3.0.7")
    implementation("io.prometheus:simpleclient:$prometheusClientVersion")
    implementation("io.prometheus:simpleclient_hotspot:$prometheusClientVersion")
    implementation("io.prometheus:simpleclient_vertx:$prometheusClientVersion")
    implementation("org.apache.commons:commons-lang3:3.8.1")
    implementation("org.flywaydb:flyway-core:5.2.4")
    implementation("org.graalvm.sdk:graal-sdk:$graalVMVersion")
    implementation("org.graalvm.js:js:$graalVMVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host-embeddable:1.3.30")
    implementation("org.pacesys:openstack4j:3.2.0")
    implementation("org.postgresql:postgresql:42.2.5")
    implementation("org.yaml:snakeyaml:1.23")

    assets("org.webjars:highlightjs:9.8.0")
    assets("org.webjars.npm:d3:5.9.2") {
        isTransitive = false
    }
    assets("org.webjars.npm:dagre:0.8.4") {
        isTransitive = false
    }
    assets("org.webjars.npm:dagre-d3:0.6.3") {
        isTransitive = false
    }
    assets("org.webjars.npm:graphlib:2.1.7") {
        isTransitive = false
    }
    assets("org.webjars.npm:jquery:3.3.1")
    assets("org.webjars.npm:lodash:4.17.11") {
        isTransitive = false
    }
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

    testImplementation("de.flapdoodle.embed:de.flapdoodle.embed.mongo:2.2.0")
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

    val minifyJs by creating
    val jsFiles = mapOf(
        "$projectDir/src/main/resources/js/agents.js" to "$buildDir/assets/assets/agents.js",
        "$projectDir/src/main/resources/js/pagination.js" to "$buildDir/assets/assets/pagination.js",
        "$projectDir/src/main/resources/js/processchains.js" to "$buildDir/assets/assets/processchains.js",
        "$projectDir/src/main/resources/js/workflows.js" to "$buildDir/assets/assets/workflows.js"
    )
    for ((i, s) in jsFiles.keys.withIndex()) {
        val t = jsFiles[s]
        val task = register<JavaExec>("minifyJs$i") {
            inputs.files(files(s))
            outputs.files(files(t))
            classpath = closureCompiler
            main = "com.google.javascript.jscomp.CommandLineRunner"
            args = listOf("--js_output_file=$t", s)
        }
        minifyJs.dependsOn(task)
    }

    val less by creating {
        val cssFiles = mapOf(
            "$projectDir/src/main/resources/css/index.less" to "$buildDir/assets/assets/index.css"
        )
        inputs.files(files(*cssFiles.keys.toTypedArray()))
        outputs.files(files(*cssFiles.values.toTypedArray()))
        doLast {
            for ((s, t) in cssFiles) {
                val r = Less.compile(file(s), true)
                file(t).writeText(r)
            }
        }
    }

    val extractAssets by creating(Sync::class) {
        dependsOn(assets)
        from(assets.map { if (it.extension == "js") it else zipTree(it) })
        include(listOf(
            "META-INF/resources/webjars/d3/5.9.2/dist/d3.min.js",
            "META-INF/resources/webjars/dagre/0.8.4/dist/dagre.core.min.js",
            "META-INF/resources/webjars/dagre-d3/0.6.3/dist/dagre-d3.core.min.js",
            "META-INF/resources/webjars/graphlib/2.1.7/dist/graphlib.core.min.js",
            "META-INF/resources/webjars/highlightjs/9.8.0/styles/zenburn.css",
            "META-INF/resources/webjars/highlightjs/9.8.0/highlight.min.js",
            "META-INF/resources/webjars/jquery/3.3.1/dist/jquery.min.js",
            "META-INF/resources/webjars/lodash/4.17.11/lodash.min.js",
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
