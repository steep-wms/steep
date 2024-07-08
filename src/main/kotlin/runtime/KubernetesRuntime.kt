package runtime

import ConfigConstants
import helper.JsonUtils
import helper.OutputCollector
import helper.UniqueID
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.Volume
import io.fabric8.kubernetes.api.model.VolumeMount
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.jsonArrayOf
import model.processchain.Executable
import org.apache.commons.lang3.exception.ExceptionUtils
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
    private val log = LoggerFactory.getLogger(KubernetesRuntime::class.java)

    const val DEFAULT_NAMESPACE = "default"

    private inline fun <reified T> deserConfig(config: JsonObject, name: String,
        humanReadableName: String, article: String = "a"): List<T> {
      val jsonArr = config.getJsonArray(name, jsonArrayOf())
      return jsonArr.map { obj ->
        if (obj is JsonObject) {
          try {
            JsonUtils.fromJson<T>(obj)
          } catch (t: Throwable) {
            throw IllegalArgumentException("Unable to deserialize element in " +
                "configuration item `$name' to $article $humanReadableName object.", t)
          }
        } else {
          throw IllegalArgumentException("Configuration item " +
              "`$name' must be an array of $humanReadableName objects. " +
              "Found: ${obj.javaClass}.")
        }
      }
    }
  }

  private val namespace: String = config.getString(
      ConfigConstants.RUNTIMES_KUBERNETES_NAMESPACE, DEFAULT_NAMESPACE)
  private val envVars = deserConfig<EnvVar>(config,
      ConfigConstants.RUNTIMES_KUBERNETES_ENV, "environment variable", "an")
  private val volumeMounts = deserConfig<VolumeMount>(config,
      ConfigConstants.RUNTIMES_KUBERNETES_VOLUMEMOUNTS, "volume mount")
  private val volumes = deserConfig<Volume>(config,
      ConfigConstants.RUNTIMES_KUBERNETES_VOLUMES, "volume")

  /**
   * Wait for a job to finish. Handles output and failures.
   */
  private fun waitForJob(client: KubernetesClient, job: Job,
      commandLine: List<String>, lazyStartWatchLog: () -> Unit) {
    // get all pods created by the job
    val podList = client.pods().inNamespace(namespace)
        .withLabel("job-name", job.metadata.name).list()

    // wait for pod to complete
    val errorMessages = mutableListOf<String>()
    var failed = false
    client.pods().inNamespace(namespace)
        .withName(podList.items[0].metadata.name)
        .waitUntilCondition({ pod ->
          if (pod.status.containerStatuses.all { it.state.running != null }) {
            // now that the container is running, we can start the watch log
            lazyStartWatchLog()
          }

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

    if (failed) {
      throw IOException("Failed to run `${commandLine.joinToString(" ")}'. " +
          "Reason: $errorMessages.")
    }
  }

  /**
   * Executes an [executable] using the given Kubernetes [client]
   */
  private fun execute(executable: Executable, outputCollector: OutputCollector,
      client: KubernetesClient) {
    val jobId = UniqueID.next()
    val jobName = "steep-${executable.id}-${jobId}-${executable.serviceId}-"
        .lowercase().replace("""[^a-z0-9]""".toRegex(), "-")
        .take(63).trimEnd { !it.isLetterOrDigit() }

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
                .withArgs(args)
                .withVolumeMounts(volumeMounts)
                .withEnv(envVars)
              .endContainer()
              .withRestartPolicy("Never")
              .withVolumes(volumes)
            .endSpec()
          .endTemplate()
        .endSpec()
        .build()

    // start job now
    client.batch().v1().jobs().inNamespace(namespace).resource(job).create()
    try {
      // A reference to a thread that reads logs from the started container.
      // This thread needs to be lazily initialized because the logs are not
      // available as long as the container is starting.
      val watchHolder = AtomicReference<Thread>(null)

      // Start the watch log thread or do nothing if it is already started
      fun lazyStartWatchLog() {
        if (watchHolder.get() == null) {
          val resource = client.batch().v1().jobs().inNamespace(namespace)
              .withName(job.metadata.name)
          val watchLog = resource.watchLog()
          val streamGobbler = StreamGobbler(jobId, watchLog.output, outputCollector,
              MDC.getCopyOfContextMap())
          val readerThread = Thread(streamGobbler)
          readerThread.start()
          watchHolder.set(readerThread)
        }
      }

      try {
        waitForJob(client, job, commandLine, ::lazyStartWatchLog)
      } finally {
        // Collect logs. We need to make sure the thread is started because
        // the container execution might be so fast, that we haven't received
        // the pod status change event and, therefore, haven't started the
        // thread yet.
        lazyStartWatchLog()
        watchHolder.get().join()
      }
    } catch (e: InterruptedException) {
      try {
        // delete pod immediately on cancel
        client.pods().inNamespace(namespace)
            .withLabel("job-name", job.metadata.name)
            .withGracePeriod(0).delete()
      } catch (t: Throwable) {
        // ignore
      }
      throw e;
    } finally {
      // make sure to delete the job after it has finished
      client.batch().v1().jobs().inNamespace(namespace)
          .withName(job.metadata.name).delete()
    }
  }

  override fun execute(executable: Executable, outputCollector: OutputCollector) {
    KubernetesClientBuilder().withConfig(kubernetesConfig).build().use { client ->
      execute(executable, outputCollector, client)
    }
  }

  /**
   * A background thread that reads all lines from the [inputStream] of a job
   * with the given [jobId] and collects them in an [outputCollector]
   */
  private class StreamGobbler(
      private val jobId: String,
      private val inputStream: InputStream,
      private val outputCollector: OutputCollector,
      private val mdc: Map<String, String>?
  ) : Runnable {
    override fun run() {
      mdc?.let { MDC.setContextMap(it) }
      inputStream.bufferedReader().forEachLine { line ->
        log.info("[$jobId] $line")
        outputCollector.collect(line)
      }
    }
  }
}
