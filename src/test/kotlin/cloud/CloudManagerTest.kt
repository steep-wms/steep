package cloud

import AddressConstants.REMOTE_AGENT_ADDED
import AddressConstants.REMOTE_AGENT_ADDRESS_PREFIX
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
import io.vertx.kotlin.core.shareddata.getAsyncMapAwait
import io.vertx.kotlin.core.shareddata.getAwait
import io.vertx.kotlin.core.shareddata.getCounterAwait
import io.vertx.kotlin.core.shareddata.incrementAndGetAwait
import io.vertx.kotlin.core.shareddata.putAwait
import io.vertx.kotlin.core.shareddata.sizeAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import model.setup.Setup
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Tests for [CloudManager]
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class CloudManagerTest {
  companion object {
    private const val MY_OLD_VM = "MY_OLD_VM"
    private const val CREATED_BY_TAG = "CloudManagerTest"
    private const val TEST_SETUP2_ID = "TestSetup2"
    private const val SYNC_INTERVAL = 2
  }

  private lateinit var client: CloudClient
  private lateinit var cloudManager: CloudManager

  @BeforeEach
  fun setUp() {
    // mock cloud client
    client = mockk()
    mockkObject(CloudClientFactory)
    every { CloudClientFactory.create(any()) } returns client

    // mock SSH client
    mockkConstructor(SSHClient::class)
    coEvery { anyConstructed<SSHClient>().tryConnect(any()) } just Runs
  }

  @AfterEach
  fun tearDown() {
    unmockkAll()
  }

  /**
   * Deploy the [CloudManager] verticle and complete the given test context
   */
  private fun deployCloudManager(tempDir: Path, setups: List<Setup>,
      vertx: Vertx, ctx: VertxTestContext) {
    // create setups file
    val tempDirFile = tempDir.toRealPath().toFile()
    val setupFile = File(tempDirFile, "test_setups.yaml")
    YamlUtils.mapper.writeValue(setupFile, setups)

    GlobalScope.launch(vertx.dispatcher()) {
      // deploy verticle under test
      val config = json {
        obj(
            ConfigConstants.CLOUD_CREATED_BY_TAG to CREATED_BY_TAG,
            ConfigConstants.CLOUD_SSH_USERNAME to "user",
            ConfigConstants.CLOUD_SSH_PRIVATE_KEY_LOCATION to "myprivatekey.pem",
            ConfigConstants.CLOUD_SETUPS_FILE to setupFile.toString(),
            ConfigConstants.CLOUD_SYNC_INTERVAL to SYNC_INTERVAL
        )
      }
      val options = DeploymentOptions(config)
      cloudManager = CloudManager()
      vertx.deployVerticle(cloudManager, options, ctx.completing())
    }
  }

  /**
   * Test that VMs are created
   */
  @Nested
  inner class Create {
    private lateinit var testSetup: Setup
    private lateinit var testSetupLarge: Setup

    @BeforeEach
    fun setUp(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
      // mock client
      coEvery { client.listVMs(any()) } returns emptyList()

      // create test setups
      val tempDirFile = tempDir.toRealPath().toFile()
      val testSh = File(tempDirFile, "test.sh")
      testSh.writeText("{{ agentId }}")

      testSetup = Setup("test", "myflavor", "myImage", 500000, null, 0, 1,
          listOf(testSh.absolutePath), listOf("test1"))
      testSetupLarge = Setup("testLarge", "myflavor", "myImage", 500000, "SSD", 0, 4,
          listOf(testSh.absolutePath), listOf("test2"))

      deployCloudManager(tempDir, listOf(testSetup, testSetupLarge), vertx, ctx)
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
          vertx.eventBus().publish(REMOTE_AGENT_ADDED,
              REMOTE_AGENT_ADDRESS_PREFIX + agentId)
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

  /**
   * Test that VMs are destroyed
   */
  @Nested
  inner class Destroy {
    @BeforeEach
    fun setUp(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
      // return a VM that should be deleted when the verticle starts up
      coEvery { client.listVMs(any()) } returns listOf(MY_OLD_VM)
      coEvery { client.isVMActive(MY_OLD_VM) } returns true
      coEvery { client.destroyVM(MY_OLD_VM) } just Runs

      GlobalScope.launch(vertx.dispatcher()) {
        // create a counter for a second setup
        val counter = vertx.sharedData().getCounterAwait(
            "CloudManager.Counter.$TEST_SETUP2_ID")
        counter.incrementAndGetAwait()

        // pretend we already created a VM with the second setup
        val createdVMs = vertx.sharedData().getAsyncMapAwait<String, String>(
            "CloudManager.CreatedVMs")
        createdVMs.putAwait(UniqueID.next(), TEST_SETUP2_ID)

        deployCloudManager(tempDir, emptyList(), vertx, ctx)
      }
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

    /**
     * Check if we have synced our internal maps on startup
     */
    @Test
    fun removeNonExistingVM(vertx: Vertx, ctx: VertxTestContext) {
      GlobalScope.launch(vertx.dispatcher()) {
        ctx.coVerify {
          // check that the counter was decreased
          val counter = vertx.sharedData().getCounterAwait(
              "CloudManager.Counter.$TEST_SETUP2_ID")
          assertThat(counter.getAwait()).isEqualTo(0)

          // check that the VM was removed from the internal map
          val createdVMs = vertx.sharedData().getAsyncMapAwait<String, String>(
              "CloudManager.CreatedVMs")
          assertThat(createdVMs.sizeAwait()).isEqualTo(0)
        }
        ctx.completeNow()
      }
    }
  }

  /**
   * Test that a minimum number of VMs is created
   */
  @Nested
  inner class Min {
    private lateinit var testSetupMin: Setup

    @BeforeEach
    fun setUp(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
      val tempDirFile = tempDir.toRealPath().toFile()
      val testSh = File(tempDirFile, "test.sh")
      testSh.writeText("{{ agentId }}")

      testSetupMin = Setup("testMin", "myflavor", "myImage", 500000, null, 2, 4,
          listOf(testSh.absolutePath))

      // mock client
      val createdVMs = mutableListOf<String>()
      coEvery { client.listVMs(any()) } answers { createdVMs }
      coEvery { client.getImageID(testSetupMin.imageName) } returns testSetupMin.imageName
      coEvery { client.createBlockDevice(testSetupMin.imageName, testSetupMin.blockDeviceSizeGb,
          testSetupMin.blockDeviceVolumeType, any()) } answers { UniqueID.next() }
      coEvery { client.createVM(testSetupMin.flavor, any(), any()) } answers {
          UniqueID.next().also { createdVMs.add(it) } }
      coEvery { client.getIPAddress(any()) } answers { UniqueID.next() }
      coEvery { client.waitForVM(any()) } just Runs

      // mock SSH agent
      var agentId = ""
      val testShSrcSlot = slot<String>()
      val testShDstSlot = slot<String>()
      coEvery { anyConstructed<SSHClient>().uploadFile(capture(testShSrcSlot),
          capture(testShDstSlot)) } answers {
        agentId = File(testShSrcSlot.captured).readText()
      }

      // send REMOTE_AGENT_ADDED when the VM has been provisioned
      coEvery { anyConstructed<SSHClient>().execute(any()) } coAnswers {
        vertx.eventBus().publish(REMOTE_AGENT_ADDED,
            REMOTE_AGENT_ADDRESS_PREFIX + agentId)
      }

      deployCloudManager(tempDir, listOf(testSetupMin), vertx, ctx)
    }

    /**
     * Make sure we always create at least two VMs with [testSetupMin]
     */
    @Test
    fun tryCreateMin(vertx: Vertx, ctx: VertxTestContext) {
      GlobalScope.launch(vertx.dispatcher()) {
        // give the CloudManager enough time to call sync() at least three times
        delay(SYNC_INTERVAL * 4 * 1000L)

        ctx.coVerify {
          coVerify(exactly = 2) {
            client.getImageID(testSetupMin.imageName)
            client.createBlockDevice(testSetupMin.imageName,
                testSetupMin.blockDeviceSizeGb,
                testSetupMin.blockDeviceVolumeType, any())
            client.createVM(testSetupMin.flavor, any(), any())
            client.getIPAddress(any())
          }
        }

        ctx.completeNow()
      }
    }
  }
}
