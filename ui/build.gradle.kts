import com.github.gradle.node.npm.task.NpmInstallTask
import com.github.gradle.node.npm.task.NpmTask

plugins {
    java
    id("com.github.node-gradle.node") version "3.5.0"
}

node {
    version.set(file(".tool-versions").readText().split(" ")[1].trim())
    download.set(true)
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
        args.set(listOf("run", "build"))

        outputs.cacheIf { true }

        for (d in listOf("assets", "components", "css", "pages")) {
            inputs.dir(file(d))
                .withPropertyName(d)
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }

        for (f in listOf(".eslintrc.json", "next.config.js")) {
            inputs.file(file(f))
                .withPropertyName(f)
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }

        inputs.files("package.json", "package-lock.json")
            .withPropertyName("package-jsons-2")
            .withPathSensitivity(PathSensitivity.RELATIVE)

        outputs.dir("out")
            .withPropertyName("out")
    }

    val ci by creating(NpmTask::class) {
        dependsOn(":installDist")
        args.set(listOf("run", "ci"))
    }

    processResources {
        dependsOn(buildUi)
    }

    clean {
        delete(file(".next"))
        delete(file("node_modules"))
        delete(file("out"))
    }
}
