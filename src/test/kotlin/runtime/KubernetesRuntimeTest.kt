package runtime

import ConfigConstants
import com.fasterxml.jackson.module.kotlin.readValue
import helper.DefaultOutputCollector
import helper.JsonUtils
import helper.UniqueID
import io.fabric8.kubernetes.api.model.LocalObjectReferenceBuilder
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.jsonArrayOf
import io.vertx.kotlin.core.json.jsonObjectOf
import model.metadata.Service
import model.processchain.Argument
import model.processchain.ArgumentVariable
import model.processchain.Executable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import runtime.ContainerRuntimeTest.Companion.EXPECTED
import java.io.IOException
import java.net.HttpURLConnection
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.io.path.absolute
import kotlin.io.path.absolutePathString

/**
 * Tests for [KubernetesRuntime]
 * @author Michel Kraemer
 */
@Testcontainers
@Execution(ExecutionMode.CONCURRENT)
class KubernetesRuntimeTest : ContainerRuntimeTest {
  companion object {
    @Container
    val k3s = K3sContainer(DockerImageName.parse("rancher/k3s:v1.30.2-k3s1"))
  }

  override fun createConfig(tempDir: Path, additional: JsonObject): JsonObject {
    val result = jsonObjectOf(
        ConfigConstants.TMP_PATH to tempDir.toString()
    )
    result.mergeIn(additional)
    return result
  }

  override fun createRuntime(config: JsonObject): Runtime {
    return KubernetesRuntime(config, Config.fromKubeconfig(k3s.kubeConfigYaml))
  }

  override fun getVolumeRuntimeArgs(tempDir: Path): List<Argument> {
    return listOf(
        Argument(
            variable = ArgumentVariable(
                "volumes",
                """
                  - name: tmp-path
                    hostPath:
                      path: ${tempDir.absolute()}
                """.trimIndent()
            ),
            type = Argument.Type.INPUT
        ),
        Argument(
            variable = ArgumentVariable(
                "volumeMounts",
                """
                  - name: tmp-path
                    mountPath: /tmp
                """.trimIndent()
            ),
            type = Argument.Type.INPUT
        )
    )
  }

  @Test
  override fun executeVolume(@TempDir tempDir: Path, @TempDir tempDir2: Path) {
    // make sure the test file exists in the K3S environment (this is not the pod!)
    k3s.execInContainer("mkdir", "-p", tempDir2.absolute().toString())
    k3s.execInContainer("sh", "-c", "echo \"$EXPECTED\" > ${tempDir2.absolute()}/test.txt")

    super.executeVolume(tempDir, tempDir2)
  }

  @Test
  override fun executeVolumeConf(@TempDir tempDir: Path, @TempDir tempDir2: Path) {
    // make sure the test file exists in the K3S environment (this is not the pod!)
    k3s.execInContainer("mkdir", "-p", tempDir2.absolute().toString())
    k3s.execInContainer("sh", "-c", "echo \"$EXPECTED\" > ${tempDir2.absolute()}/test.txt")

    doExecuteVolumeConf(tempDir, tempDir2, jsonObjectOf(
        ConfigConstants.RUNTIMES_KUBERNETES_VOLUMES to jsonArrayOf(
            jsonObjectOf(
                "name" to "tmp-path",
                "hostPath" to jsonObjectOf(
                    "path" to tempDir2.absolutePathString()
                )
            )
        ),
        ConfigConstants.RUNTIMES_KUBERNETES_VOLUMEMOUNTS to jsonArrayOf(
            jsonObjectOf(
                "name" to "tmp-path",
                "mountPath" to "/tmp"
            )
        )
    ))
  }

  override fun getEnvRuntimeArgs(): List<Argument> {
    return listOf(
        Argument(
            variable = ArgumentVariable(
                "env",
                """
                  - name: MYVAR
                    value: $EXPECTED
                """.trimIndent()
            ),
            type = Argument.Type.INPUT
        ),
    )
  }

  @Test
  override fun executeEnvConf(@TempDir tempDir: Path) {
    doExecuteEnvConf(tempDir, jsonObjectOf(
        ConfigConstants.RUNTIMES_KUBERNETES_ENV to jsonArrayOf(
            jsonObjectOf(
                "name" to "MYVAR",
                "value" to EXPECTED
            )
        )
    ))
  }

  /**
   * Check that the runtime deletes the job after is has finished successfully
   */
  @Test
  fun deleteJob(@TempDir tempDir: Path) {
    val exec = Executable(path = "alpine", serviceId = "echo", arguments = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), "echo"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), EXPECTED),
            type = Argument.Type.INPUT)
    ), runtime = Service.RUNTIME_KUBERNETES)

    val runtime = createRuntime(createConfig(tempDir))
    val collector = DefaultOutputCollector()
    runtime.execute(exec, collector)

    val kubernetesConfig = Config.fromKubeconfig(k3s.kubeConfigYaml)
    KubernetesClientBuilder().withConfig(kubernetesConfig).build().use { client ->
      val jobs = client.batch().v1().jobs().inNamespace(KubernetesRuntime.DEFAULT_NAMESPACE).list()
      assertThat(jobs.items).noneMatch { it.metadata.name.startsWith("steep-${exec.id}") }
      val pods = client.pods().inNamespace(KubernetesRuntime.DEFAULT_NAMESPACE).list()
      assertThat(pods.items).noneMatch { it.metadata.name.startsWith("steep-${exec.id}") }
    }
  }

  /**
   * Check that the runtime deletes the job after is has finished with an error
   */
  @Test
  fun deleteJobAfterFailure(@TempDir tempDir: Path) {
    val exec = Executable(path = "alpine", serviceId = "false", arguments = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), "false"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), EXPECTED),
            type = Argument.Type.INPUT)
    ), runtime = Service.RUNTIME_KUBERNETES)

    val runtime = createRuntime(createConfig(tempDir))
    val collector = DefaultOutputCollector()
    assertThatThrownBy { runtime.execute(exec, collector) }
        .isInstanceOf(IOException::class.java)

    val kubernetesConfig = Config.fromKubeconfig(k3s.kubeConfigYaml)
    KubernetesClientBuilder().withConfig(kubernetesConfig).build().use { client ->
      val jobs = client.batch().v1().jobs().inNamespace(KubernetesRuntime.DEFAULT_NAMESPACE).list()
      assertThat(jobs.items).noneMatch { it.metadata.name.startsWith("steep-${exec.id}") }
      val pods = client.pods().inNamespace(KubernetesRuntime.DEFAULT_NAMESPACE).list()
      assertThat(pods.items).noneMatch { it.metadata.name.startsWith("steep-${exec.id}") }
    }
  }

  /**
   * Make sure the pod is immediately deleted when the executable is cancelled
   */
  @Test
  fun killPodOnCancel(@TempDir tempDir: Path) {
    val seconds = 120
    val exec = Executable(path = "alpine", serviceId = "sleep", arguments = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), "sleep"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), seconds.toString()),
            type = Argument.Type.INPUT)
    ), runtime = Service.RUNTIME_KUBERNETES)

    // launch a job in the background
    val executor = Executors.newSingleThreadExecutor()
    val runtime = createRuntime(createConfig(tempDir))
    val execFuture = executor.submit {
      try {
        runtime.execute(exec, DefaultOutputCollector())
      } catch (t: Throwable) {
        t.printStackTrace()
        fail(t)
      }
    }

    val kubernetesConfig = Config.fromKubeconfig(k3s.kubeConfigYaml)
    KubernetesClientBuilder().withConfig(kubernetesConfig).build().use { client ->
      // wait until the pod is there
      while (true) {
        val pods = client.pods().list().items
        val sleepPod = pods.any { it.metadata.name.startsWith("steep-${exec.id}-") }
        if (sleepPod) {
          break
        }
        Thread.sleep(1000)
      }

      // cancel job
      execFuture.cancel(true)

      // wait for the pod to disappear (should be immediate, but we'll give
      // it some leeway to avoid becoming flaky - it might take some time to
      // start the job)
      var count = 0
      while (true) {
        val pods = client.pods().list().items
        val sleepPodGone = pods.none { it.metadata.name.startsWith("steep-${exec.id}-") }
        if (sleepPodGone) {
          break
        }
        Thread.sleep(1000)
        count++
        if (count == seconds / 2) {
          fail("It took too long to delete the pod!")
        }
      }
    }
  }

  @Nested
  @EnableKubernetesMockClient
  @Execution(ExecutionMode.SAME_THREAD)
  inner class Pull {
    lateinit var mockServer: KubernetesMockServer
    lateinit var mockClient: KubernetesClient

    private fun simpleExecute(tempDir: Path, additionalConfig: JsonObject = jsonObjectOf(),
        runtimeArgs: List<Argument> = emptyList()) {
      mockServer.expect().post().withPath("/apis/batch/v1/namespaces/default/jobs")
          .andReturn(HttpURLConnection.HTTP_CREATED, JobBuilder().build()).once()

      val exec = Executable(path = "alpine", serviceId = "echo", arguments = listOf(
          Argument(variable = ArgumentVariable(UniqueID.next(), "echo"),
              type = Argument.Type.INPUT),
          Argument(variable = ArgumentVariable(UniqueID.next(), EXPECTED),
              type = Argument.Type.INPUT)
      ), runtimeArgs = runtimeArgs)

      val config = mockClient.configuration
      val runtime = KubernetesRuntime(createConfig(tempDir, additionalConfig), config)
      val collector = DefaultOutputCollector()
      try {
        runtime.execute(exec, collector)
      } catch (t: Throwable) {
        // will fail because we only mock one request
        t.printStackTrace()
      }
    }

    /**
     * Test if the default values of `imagePullPolicy` and `imagePullSecrets`
     * are `null` and empty list respectively
     */
    @Test
    fun default(@TempDir tempDir: Path) {
      simpleExecute(tempDir)

      val r = mockServer.takeRequest()
      val job: Job = JsonUtils.mapper.readValue(r.body.inputStream())
      assertThat(job.spec.template.spec.containers.first().imagePullPolicy).isNull()
      assertThat(job.spec.template.spec.imagePullSecrets).isEmpty()
    }

    /**
     * Test if the configured image pull policy and the image pull secrets are
     * forwarded
     */
    @Test
    fun forwardConfigured(@TempDir tempDir: Path) {
      simpleExecute(tempDir, additionalConfig = jsonObjectOf(
          ConfigConstants.RUNTIMES_KUBERNETES_IMAGEPULLPOLICY to "Never",
          ConfigConstants.RUNTIMES_KUBERNETES_IMAGEPULLSECRETS to jsonArrayOf(
              jsonObjectOf("name" to "mysecret")
          )
      ))

      val r = mockServer.takeRequest()
      val job: Job = JsonUtils.mapper.readValue(r.body.inputStream())
      assertThat(job.spec.template.spec.containers.first().imagePullPolicy).isEqualTo("Never")
      assertThat(job.spec.template.spec.imagePullSecrets).containsOnly(
          LocalObjectReferenceBuilder().withName("mysecret").build())
    }

    /**
     * Test if we can set an image pull policy and image pull secrets on the
     * executable
     */
    @Test
    fun onExecutable(@TempDir tempDir: Path) {
      simpleExecute(tempDir, runtimeArgs = listOf(
          Argument(
              variable = ArgumentVariable("imagePullPolicy", "Never"),
              type = Argument.Type.INPUT
          ),
          Argument(
              variable = ArgumentVariable(
                  "imagePullSecrets",
                  """
                    - name: mysecret
                  """.trimIndent()
              ),
              type = Argument.Type.INPUT
          )
      ))

      val r = mockServer.takeRequest()
      val job: Job = JsonUtils.mapper.readValue(r.body.inputStream())
      assertThat(job.spec.template.spec.containers.first().imagePullPolicy).isEqualTo("Never")
      assertThat(job.spec.template.spec.imagePullSecrets).containsOnly(
          LocalObjectReferenceBuilder().withName("mysecret").build())
    }

    /**
     * Test if the configured image pull policy and the image pull secrets can
     * be overridden
     */
    @Test
    fun overwriteConfigured(@TempDir tempDir: Path) {
      simpleExecute(tempDir, additionalConfig = jsonObjectOf(
          ConfigConstants.RUNTIMES_KUBERNETES_IMAGEPULLPOLICY to "Never",
          ConfigConstants.RUNTIMES_KUBERNETES_IMAGEPULLSECRETS to jsonArrayOf(
              jsonObjectOf("name" to "mysecret")
          )
      ), runtimeArgs = listOf(
          Argument(
              variable = ArgumentVariable("imagePullPolicy", "Always"),
              type = Argument.Type.INPUT
          ),
          Argument(
              variable = ArgumentVariable(
                  "imagePullSecrets",
                  """
                    - name: anothersecret
                  """.trimIndent()
              ),
              type = Argument.Type.INPUT
          )
      ))

      val r = mockServer.takeRequest()
      val job: Job = JsonUtils.mapper.readValue(r.body.inputStream())
      assertThat(job.spec.template.spec.containers.first().imagePullPolicy).isEqualTo("Always")
      assertThat(job.spec.template.spec.imagePullSecrets).containsOnly(
          LocalObjectReferenceBuilder().withName("mysecret").build(),
          LocalObjectReferenceBuilder().withName("anothersecret").build()
      )
    }
  }
}
