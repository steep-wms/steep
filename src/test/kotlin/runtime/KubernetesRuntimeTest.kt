package runtime

import helper.DefaultOutputCollector
import helper.UniqueID
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.vertx.kotlin.core.json.jsonObjectOf
import model.metadata.Service
import model.processchain.Argument
import model.processchain.ArgumentVariable
import model.processchain.Executable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Tests for [KubernetesRuntime]
 * @author Michel Kraemer
 */
@Testcontainers
class KubernetesRuntimeTest {
  companion object {
    private const val EXPECTED = "Elvis\n"

    @Container
    val k3s = K3sContainer(DockerImageName.parse("rancher/k3s:v1.30.2-k3s1"))
  }

  /**
   * Test that a simple job can be executed and that its output can be collected
   */
  @Test
  fun executeEcho() {
    val exec = Executable(path = "alpine", serviceId = "echo", arguments = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), "echo"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), EXPECTED),
            type = Argument.Type.INPUT)
    ), runtime = Service.RUNTIME_KUBERNETES)

    val runtime = KubernetesRuntime(jsonObjectOf(), Config.fromKubeconfig(k3s.kubeConfigYaml))
    val collector = DefaultOutputCollector()
    runtime.execute(exec, collector)
    assertThat(collector.output()).isEqualTo(EXPECTED)
  }

  /**
   * Test that a simple Docker container can be executed and that its output
   * (multiple lines) can be collected
   */
  @Test
  fun executeEchoMultiline() {
    val exec = Executable(path = "alpine", serviceId = "myservice", arguments = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), "sh"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), "-c"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), "echo Hello && sleep 0.1 && echo World"),
            type = Argument.Type.INPUT)
    ), runtime = Service.RUNTIME_KUBERNETES)

    val runtime = KubernetesRuntime(jsonObjectOf(), Config.fromKubeconfig(k3s.kubeConfigYaml))
    val collector = DefaultOutputCollector()
    runtime.execute(exec, collector)
    assertThat(collector.output()).isEqualTo("Hello\nWorld")
  }

  /**
   * Test that a failure is correctly detected
   */
  @Test
  fun failure() {
    val exec = Executable(path = "alpine", serviceId = "false", arguments = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), "false"),
            type = Argument.Type.INPUT),
    ), runtime = Service.RUNTIME_KUBERNETES)

    val runtime = KubernetesRuntime(jsonObjectOf(), Config.fromKubeconfig(k3s.kubeConfigYaml))
    val collector = DefaultOutputCollector()
    assertThatThrownBy { runtime.execute(exec, collector) }
        .isInstanceOf(IOException::class.java)
  }

  /**
   * Check that the runtime deletes the job after is has finished successfully
   */
  @Test
  fun deleteJob() {
    val exec = Executable(path = "alpine", serviceId = "echo", arguments = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), "echo"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), EXPECTED),
            type = Argument.Type.INPUT)
    ), runtime = Service.RUNTIME_KUBERNETES)

    val kubernetesConfig = Config.fromKubeconfig(k3s.kubeConfigYaml)
    val runtime = KubernetesRuntime(jsonObjectOf(), kubernetesConfig)
    val collector = DefaultOutputCollector()
    runtime.execute(exec, collector)

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
  fun deleteJobAfterFailure() {
    val exec = Executable(path = "alpine", serviceId = "false", arguments = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), "false"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), EXPECTED),
            type = Argument.Type.INPUT)
    ), runtime = Service.RUNTIME_KUBERNETES)

    val kubernetesConfig = Config.fromKubeconfig(k3s.kubeConfigYaml)
    val runtime = KubernetesRuntime(jsonObjectOf(), kubernetesConfig)
    val collector = DefaultOutputCollector()
    assertThatThrownBy { runtime.execute(exec, collector) }
        .isInstanceOf(IOException::class.java)

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
  fun killPodOnCancel() {
    val exec = Executable(path = "alpine", serviceId = "sleep", arguments = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), "sleep"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), "120"),
            type = Argument.Type.INPUT)
    ), runtime = Service.RUNTIME_KUBERNETES)

    // launch a job in the background
    val kubernetesConfig = Config.fromKubeconfig(k3s.kubeConfigYaml)
    val executor = Executors.newSingleThreadExecutor()
    val execFuture = executor.submit {
      val runtime = KubernetesRuntime(jsonObjectOf(), kubernetesConfig)
      runtime.execute(exec, DefaultOutputCollector())
    }

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
}
