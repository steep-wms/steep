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
  private lateinit var testSetupLarge: Setup
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
    testSetup = Setup("test", "myflavor", "myImage", 500000, null, 1,
        listOf(testSh.absolutePath), listOf("test1"))
    testSetupLarge = Setup("testLarge", "myflavor", "myImage", 500000, "SSD", 4,
        listOf(testSh.absolutePath), listOf("test2"))
    val setupFile = File(tempDirFile, "test_setups.yaml")
    YamlUtils.mapper.writeValue(setupFile, listOf(testSetup, testSetupLarge))

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

  private suspend fun doCreateOnDemand(setup: Setup, vertx: Vertx,
      ctx: VertxTestContext) {
    val metadata = mapOf(
        "Created-By" to CREATED_BY_TAG,
        "Setup-Id" to setup.id
    )

    coEvery { client.getImageID(setup.imageName) } returns setup.imageName
    coEvery { client.createBlockDevice(setup.imageName, setup.blockDeviceSizeGb,
        setup.blockDeviceVolumeType, metadata) } answers { UniqueID.next() }
    coEvery { client.createVM(setup.flavor, any(), metadata) } answers { UniqueID.next() }
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

    cloudManager.createRemoteAgent(setup.providedCapabilities.toSet())
  }

  /**
   * Test if a VM with [testSetup] can be created on demand
   */
  @Test
  fun createVMOnDemand(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      doCreateOnDemand(testSetup, vertx, ctx)

      ctx.coVerify {
        coVerify(exactly = 1) {
          client.getImageID(testSetup.imageName)
          client.createBlockDevice(testSetup.imageName,
              testSetup.blockDeviceSizeGb, null, any())
          client.createVM(testSetup.flavor, any(), any())
          client.getIPAddress(any())
        }
      }

      ctx.completeNow()
    }
  }

  /**
   * Make sure we can only create one VM with [testSetup] at a time
   */
  @Test
  fun tryCreateTwoAsync(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val d1 = async { doCreateOnDemand(testSetup, vertx, ctx) }
      val d2 = async { doCreateOnDemand(testSetup, vertx, ctx) }

      d1.await()
      d2.await()

      ctx.coVerify {
        coVerify(exactly = 1) {
          client.getImageID(testSetup.imageName)
          client.createBlockDevice(testSetup.imageName,
              testSetup.blockDeviceSizeGb, null, any())
          client.createVM(testSetup.flavor, any(), any())
          client.getIPAddress(any())
        }
      }

      ctx.completeNow()
    }
  }

  /**
   * Make sure we can only create one VM with [testSetup] at all
   */
  @Test
  fun tryCreateTwoSync(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      doCreateOnDemand(testSetup, vertx, ctx)
      doCreateOnDemand(testSetup, vertx, ctx)

      ctx.coVerify {
        coVerify(exactly = 1) {
          client.getImageID(testSetup.imageName)
          client.createBlockDevice(testSetup.imageName,
              testSetup.blockDeviceSizeGb, null, any())
          client.createVM(testSetup.flavor, any(), any())
          client.getIPAddress(any())
        }
      }

      ctx.completeNow()
    }
  }

  /**
   * Make sure we can only create four VMs with [testSetupLarge] at all
   */
  @Test
  fun tryCreateFiveSync(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      doCreateOnDemand(testSetupLarge, vertx, ctx)
      doCreateOnDemand(testSetupLarge, vertx, ctx)
      doCreateOnDemand(testSetupLarge, vertx, ctx)
      doCreateOnDemand(testSetupLarge, vertx, ctx)
      doCreateOnDemand(testSetupLarge, vertx, ctx)

      ctx.coVerify {
        coVerify(exactly = 4) {
          client.getImageID(testSetupLarge.imageName)
          client.createBlockDevice(testSetupLarge.imageName,
              testSetupLarge.blockDeviceSizeGb, "SSD", any())
          client.createVM(testSetupLarge.flavor, any(), any())
          client.getIPAddress(any())
        }
      }

      ctx.completeNow()
    }
  }
}
