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
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import java.io.IOException

/**
 * Tests for [KubernetesRuntime]
 * @author Michel Kraemer
 */
@Testcontainers
class KubernetesRuntimeTest {
  companion object {
    private const val EXPECTED = "Elvis"

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
    assertThat(collector.output().trim()).isEqualTo(EXPECTED)
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
}
