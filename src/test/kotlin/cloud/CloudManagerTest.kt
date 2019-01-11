package cloud

import AddressConstants
import ConfigConstants
import coVerify
import helper.UniqueID
import helper.YamlUtils
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.vertx.core.Vertx
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.DeploymentOptions
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import model.setup.Setup
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.support.io.TempDirectory
import org.junit.jupiter.api.support.io.TempDirectory.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Tests for [CloudManager]
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class, TempDirectory::class)
class CloudManagerTest {
  companion object {
    private const val MY_OLD_VM = "MY_OLD_VM"
    private const val CREATED_BY_TAG = "CloudManagerTest"
  }

  private lateinit var client: CloudClient
  private lateinit var testSetup: Setup
  private lateinit var cloudManager: CloudManager

  @BeforeEach
  fun setUp(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
    // mock cloud client
    client = mockk()
    mockkObject(CloudClientFactory)
    every { CloudClientFactory.create(any()) } returns client

    // mock SSH client
    mockkConstructor(SSHClient::class)

    // create setups file
    val tempDirFile = tempDir.toRealPath().toFile()
    val testSh = File(tempDirFile, "test.sh")
    testSh.writeText("{{ agentId }}")
    testSetup = Setup("test", "myflavor", "myImage", 500000, 1, listOf(testSh.absolutePath))
    val setupFile = File(tempDirFile, "test_setups.yaml")
    YamlUtils.mapper.writeValue(setupFile, listOf(testSetup))

    // return a VM that should be deleted when the verticle starts up
    coEvery { client.listVMs(any()) } returns listOf(MY_OLD_VM)
    coEvery { client.destroyVM(MY_OLD_VM) } just Runs

    // deploy verticle under test
    val config = json {
      obj(
          ConfigConstants.CLOUD_CREATED_BY_TAG to CREATED_BY_TAG,
          ConfigConstants.CLOUD_SSH_USERNAME to "user",
          ConfigConstants.CLOUD_SSH_PRIVATE_KEY_LOCATION to "myprivatekey.pem",
          ConfigConstants.CLOUD_SETUPS_FILE to setupFile.toString()
      )
    }
    val options = DeploymentOptions(config)
    cloudManager = CloudManager()
    vertx.deployVerticle(cloudManager, options, ctx.completing())
  }

  @AfterEach
  fun tearDown() {
    unmockkAll()
  }

  /**
   * Check if our old VM has been deleted on startup
   */
  @Test
  fun destroyExistingVM(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        coVerify(exactly = 1) {
          client.destroyVM(MY_OLD_VM)
        }
      }
      ctx.completeNow()
    }
  }

  private suspend fun doCreateOnDemand(vertx: Vertx, ctx: VertxTestContext) {
    val capabilities = setOf<String>()

    val metadata = mapOf(
        "Created-By" to CREATED_BY_TAG,
        "Setup-Id" to testSetup.id
    )

    coEvery { client.getImageID(testSetup.imageName) } returns testSetup.imageName
    coEvery { client.createBlockDevice(testSetup.imageName, testSetup.blockDeviceSizeGb,
        metadata) } answers { UniqueID.next() }
    coEvery { client.createVM(testSetup.flavor, any(), metadata) } answers { UniqueID.next() }
    coEvery { client.getIPAddress(any()) } answers { UniqueID.next() }
    coEvery { client.waitForVM(any()) } just Runs

    var agentId = ""
    val testShSrcSlot = slot<String>()
    val testShDstSlot = slot<String>()
    val executeSlot = slot<String>()
    coEvery { anyConstructed<SSHClient>().tryConnect(any()) } just Runs
    coEvery { anyConstructed<SSHClient>().uploadFile(capture(testShSrcSlot),
        capture(testShDstSlot)) } answers {
      agentId = File(testShSrcSlot.captured).readText()
    }

    coEvery { anyConstructed<SSHClient>().execute(capture(executeSlot)) } coAnswers {
      if (executeSlot.captured != "sudo ${testShDstSlot.captured}") {
        ctx.verify {
          assertThat(executeSlot.captured).isEqualTo("sudo chmod +x ${testShDstSlot.captured}")
        }
      } else {
        ctx.verify {
          assertThat(agentId).isNotEmpty()
        }
        vertx.eventBus().publish(AddressConstants.REMOTE_AGENT_AVAILABLE, agentId)
      }
    }

    cloudManager.createRemoteAgent(capabilities)
  }

  /**
   * Test if a VM with [testSetup] can be created on demand
   */
  @Test
  fun createVMOnDemand(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      doCreateOnDemand(vertx, ctx)

      ctx.coVerify {
        coVerify(exactly = 1) {
          client.getImageID(testSetup.imageName)
          client.createBlockDevice(testSetup.imageName,
              testSetup.blockDeviceSizeGb, any())
          client.createVM(testSetup.flavor, any(), any())
          client.getIPAddress(any())
        }
      }

      ctx.completeNow()
    }
  }

  /**
   * Test if we can only create one VM with [testSetup]
   */
  @Test
  fun tryCreateTwoAsync(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val d1 = async { doCreateOnDemand(vertx, ctx) }
      val d2 = async { doCreateOnDemand(vertx, ctx) }

      d1.await()
      d2.await()

      ctx.coVerify {
        coVerify(exactly = 1) {
          client.getImageID(testSetup.imageName)
          client.createBlockDevice(testSetup.imageName,
              testSetup.blockDeviceSizeGb, any())
          client.createVM(testSetup.flavor, any(), any())
          client.getIPAddress(any())
        }
      }

      ctx.completeNow()
    }
  }
}
