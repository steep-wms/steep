package runtime

import helper.OutputCollector
import helper.UniqueID
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.vertx.core.json.JsonObject
import model.processchain.Executable
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Runs executables as Kubernetes jobs. Uses the executable's path as the
 * container image name.
 * @author Michel Kraemer
 */
class KubernetesRuntime(
    val config: JsonObject,
    private val kubernetesConfig: Config? = ConfigBuilder().build()
) : Runtime {
  companion object {
    const val DEFAULT_NAMESPACE = "default"
  }

  private val namespace: String = config.getString(
      ConfigConstants.RUNTIMES_KUBERNETES_NAMESPACE, DEFAULT_NAMESPACE)

  /**
   * Wait for a job to finish. Handles output and failures.
   */
  private fun waitForJob(client: KubernetesClient, job: Job,
      commandLine: List<String>, outputCollector: OutputCollector) {
    // get all pods created by the job
    val podList = client.pods().inNamespace(namespace)
        .withLabel("job-name", job.metadata.name).list()

    // wait for pod to complete
    val errorMessages = mutableListOf<String>()
    var failed = false
    client.pods().inNamespace(namespace)
        .withName(podList.items[0].metadata.name)
        .waitUntilCondition({ pod ->
          when (pod.status.phase) {
            "Succeeded" -> true
            "Failed" -> {
              if (pod.status.message != null) {
                errorMessages.add(pod.status.message)
              }
              for (containerStatus in pod.status.containerStatuses) {
                if (containerStatus?.state?.terminated?.message != null) {
                  errorMessages.add(containerStatus.state.terminated.message)
                }
              }
              failed = true
              true
            }
            "Unknown" -> {
              errorMessages.add("Pod status could not be obtained")
              failed = true
              true
            }
            else -> false
          }
        }, Long.MAX_VALUE, TimeUnit.DAYS)

    // get job log
    val joblog = client.batch().v1().jobs().inNamespace(namespace).withName(job.metadata.name).log
    for (line in joblog.lines()) {
      outputCollector.collect(line)
    }

    if (failed) {
      throw IOException(
          """
              Failed to run `${commandLine.joinToString(" ")}'.

              Reason: $errorMessages.

              Last output: ${outputCollector.output()}
            """.trimIndent()
      )
    }
  }

  /**
   * Executes an [executable] using the given Kubernetes [client]
   */
  private fun execute(executable: Executable, outputCollector: OutputCollector,
      client: KubernetesClient) {
    val jobName = "steep-${executable.id}-${executable.serviceId}-${UniqueID.next()}"
        .lowercase().replace("""[^a-z0-9]""".toRegex(), "-")

    val commandLine = Runtime.executableToCommandLine(executable)
    val args = commandLine.drop(1)

    // create job
    val job = JobBuilder()
        .withApiVersion("batch/v1")
        .withNewMetadata()
          .withName(jobName)
        .endMetadata()
        .withNewSpec()
          .withNewTemplate()
            .withNewSpec()
              .addNewContainer()
                .withName(jobName)
                .withImage(executable.path)
                .withArgs(*args.toTypedArray())
              .endContainer()
              .withRestartPolicy("Never")
            .endSpec()
          .endTemplate()
        .endSpec()
        .build()

    val jobs = client.batch().v1().jobs().inNamespace(namespace)
    jobs.resource(job).create()

    try {
      waitForJob(client, job, commandLine, outputCollector)
    } finally {
      // make sure to delete the job after it has finished
      jobs.resource(job).delete()
    }
  }

  override fun execute(executable: Executable, outputCollector: OutputCollector) {
    KubernetesClientBuilder().withConfig(kubernetesConfig).build().use { client ->
      execute(executable, outputCollector, client)
    }
  }
}
