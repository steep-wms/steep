import com.moowork.gradle.node.npm.NpmInstallTask
import com.moowork.gradle.node.npm.NpmTask

plugins {
    java
    id("com.github.node-gradle.node") version "2.2.3"
}

node {
    version = file(".tool-versions").readText().split(" ")[1].trim()
    download = true
}

tasks {
    val installUi by creating(NpmInstallTask::class) {
        outputs.cacheIf { true }

        inputs.files("package.json", "package-lock.json")
            .withPropertyName("package-jsons-1")
            .withPathSensitivity(PathSensitivity.RELATIVE)

        outputs.dir("node_modules")
            .withPropertyName("node_modules")
    }

    val buildUi by creating(NpmTask::class) {
        dependsOn(installUi)
        setArgs(listOf("run", "build"))

        outputs.cacheIf { true }

        for (d in listOf("assets", "components", "css", "pages")) {
            inputs.dir(file(d))
                .withPropertyName(d)
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }

        inputs.files("package.json", "package-lock.json")
            .withPropertyName("package-jsons-2")
            .withPathSensitivity(PathSensitivity.RELATIVE)

        outputs.dir("out")
            .withPropertyName("out")
    }

    processResources {
        dependsOn(buildUi)
    }
}
