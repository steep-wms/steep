import db.VMRegistry
import db.VMRegistryFactory
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import model.cloud.VM
import model.setup.Setup
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Tests for functions defined in `Main.kt`
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class MainTest {
  /**
   * Test if Hazelcast members can be restored from still running VMs
   */
  @Test
  fun restoreMembers(ctx: VertxTestContext) {
    val ip1 = "127.0.0.1"
    val ip3 = "192.0.0.1"
    val defaultPort = 6000
    val setup = Setup(id = "test-setup", flavor = "myflavor",
        imageName = "myimage", availabilityZone = "my-az", blockDeviceSizeGb = 20,
        maxVMs = 10)
    val vm1 = VM(setup = setup, ipAddress = ip1)
    val vm2 = VM(setup = setup)
    val vm3 = VM(setup = setup, ipAddress = ip3)

    // mock VM registry
    val vmRegistry = mockk<VMRegistry>()
    mockkObject(VMRegistryFactory)
    every { VMRegistryFactory.create(any()) } returns vmRegistry
    coEvery { vmRegistry.findNonTerminatedVMs() } returns listOf(vm1, vm2, vm3)
    coEvery { vmRegistry.close() } just Runs

    GlobalScope.launch {
      val members = restoreMembers(defaultPort)
      assertThat(members).containsExactlyInAnyOrder("$ip1:$defaultPort",
          "$ip3:$defaultPort")
      ctx.completeNow()
    }
  }
}
