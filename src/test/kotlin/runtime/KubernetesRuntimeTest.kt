package runtime

import ConfigConstants
import helper.DefaultOutputCollector
import helper.UniqueID
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.jsonArrayOf
import io.vertx.kotlin.core.json.jsonObjectOf
import model.metadata.Service
import model.processchain.Argument
import model.processchain.ArgumentVariable
import model.processchain.Executable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import runtime.ContainerRuntimeTest.Companion.EXPECTED
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.io.path.absolute

/**
 * Tests for [KubernetesRuntime]
 * @author Michel Kraemer
 */
@Testcontainers
class KubernetesRuntimeTest : ContainerRuntimeTest {
  companion object {
    @Container
    val k3s = K3sContainer(DockerImageName.parse("rancher/k3s:v1.30.2-k3s1"))
  }

  override fun createDefaultConfig(tempDir: Path): JsonObject {
    return jsonObjectOf(
        ConfigConstants.TMP_PATH to tempDir.toString(),
        ConfigConstants.RUNTIMES_KUBERNETES_VOLUMES to jsonArrayOf(
            jsonObjectOf(
                "name" to "tmp-path",
                "hostPath" to jsonObjectOf(
                    "path" to tempDir.absolute().toString()
                )
            )
        ),
        ConfigConstants.RUNTIMES_KUBERNETES_VOLUMEMOUNTS to jsonArrayOf(
            jsonObjectOf(
                "name" to "tmp-path",
                "mountPath" to tempDir.absolute().toString()
            )
        )
    )
  }

  override fun createRuntime(config: JsonObject): Runtime {
    return KubernetesRuntime(config, Config.fromKubeconfig(k3s.kubeConfigYaml))
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

    val runtime = createRuntime(createDefaultConfig(tempDir))
    val collector = DefaultOutputCollector()
    runtime.execute(exec, collector)

    val kubernetesConfig = Config.fromKubeconfig(k3s.kubeConfigYaml)
    KubernetesClientBuilder().withConfig(kubernetesConfig).build().use { client ->
      val jobs = client.batch().v1().jobs().inNamespace(KubernetesRuntime.DEFAULT_NAMESPACE).list()
      assertThat(jobs.items).isEmpty()
      val pods = client.pods().inNamespace(KubernetesRuntime.DEFAULT_NAMESPACE).list()
      assertThat(pods.items).isEmpty()
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

    val runtime = createRuntime(createDefaultConfig(tempDir))
    val collector = DefaultOutputCollector()
    assertThatThrownBy { runtime.execute(exec, collector) }
        .isInstanceOf(IOException::class.java)

    val kubernetesConfig = Config.fromKubeconfig(k3s.kubeConfigYaml)
    KubernetesClientBuilder().withConfig(kubernetesConfig).build().use { client ->
      val jobs = client.batch().v1().jobs().inNamespace(KubernetesRuntime.DEFAULT_NAMESPACE).list()
      assertThat(jobs.items).isEmpty()
      val pods = client.pods().inNamespace(KubernetesRuntime.DEFAULT_NAMESPACE).list()
      assertThat(pods.items).isEmpty()
    }
  }

  /**
   * Make sure the pod is immediately deleted when the executable is cancelled
   */
  @Test
  fun killPodOnCancel(@TempDir tempDir: Path) {
    val exec = Executable(path = "alpine", serviceId = "sleep", arguments = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), "sleep"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), "120"),
            type = Argument.Type.INPUT)
    ), runtime = Service.RUNTIME_KUBERNETES)

    // launch a job in the background
    val executor = Executors.newSingleThreadExecutor()
    val execFuture = executor.submit {
      val runtime = createRuntime(createDefaultConfig(tempDir))
      runtime.execute(exec, DefaultOutputCollector())
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
      // it some leeway to avoid becoming flaky)
      var count = 0
      while (true) {
        val pods = client.pods().list().items
        val sleepPodGone = pods.none { it.metadata.name.startsWith("steep-${exec.id}-") }
        if (sleepPodGone) {
          break
        }
        Thread.sleep(1000)
        count++
        if (count == 2) {
          fail("It took too long to delete the pod!")
        }
      }
    }
  }

  @Test
  override fun executeTmpPath(@TempDir tempDir: Path) {
    k3s.execInContainer("mkdir", "-p", tempDir.absolute().toString())
    k3s.execInContainer("sh", "-c", "echo \"$EXPECTED\" > ${tempDir.absolute()}/test.txt")
    super.executeTmpPath(tempDir)
  }
}
