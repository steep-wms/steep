import AddressConstants.LOCAL_AGENT_ADDRESS_PREFIX
import AddressConstants.REMOTE_AGENT_ADDRESS_PREFIX
import AddressConstants.REMOTE_AGENT_PROCESSCHAINLOGS_SUFFIX
import agent.AgentRegistry
import agent.AgentRegistryFactory
import com.fasterxml.jackson.module.kotlin.convertValue
import db.MetadataRegistry
import db.MetadataRegistryFactory
import db.PluginRegistry
import db.PluginRegistryFactory
import db.SetupRegistry
import db.SetupRegistryFactory
import db.SubmissionRegistry
import db.SubmissionRegistry.ProcessChainStatus
import db.SubmissionRegistryFactory
import db.VMRegistry
import db.VMRegistryFactory
import helper.JsonUtils
import helper.UniqueID
import helper.YamlUtils
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.predicate.ResponsePredicate
import io.vertx.ext.web.client.predicate.ResponsePredicate.contentType
import io.vertx.ext.web.codec.BodyCodec
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.deploymentOptionsOf
import io.vertx.kotlin.core.json.jsonArrayOf
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.awaitResult
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import model.Submission
import model.cloud.VM
import model.metadata.Cardinality
import model.metadata.Service
import model.metadata.ServiceParameter
import model.plugins.InitializerPlugin
import model.plugins.OutputAdapterPlugin
import model.plugins.Plugin
import model.plugins.ProcessChainAdapterPlugin
import model.plugins.ProcessChainConsistencyCheckerPlugin
import model.plugins.ProgressEstimatorPlugin
import model.plugins.RuntimePlugin
import model.processchain.Argument
import model.processchain.Executable
import model.processchain.ProcessChain
import model.processchain.Run
import model.setup.Setup
import model.workflow.ExecuteAction
import model.workflow.ForEachAction
import model.workflow.Variable
import model.workflow.Workflow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.extension.ExtendWith
import search.QueryCompiler
import search.SearchResult
import search.Type
import java.net.ServerSocket
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter.ISO_INSTANT
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * Tests for [HttpEndpoint]
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class HttpEndpointTest {
  companion object {
    private const val ENABLE_SETUPS = "enable-setups"
  }

  private val maxPostSize = 1024
  private var port: Int = 0
  private lateinit var agentRegistry: AgentRegistry
  private lateinit var submissionRegistry: SubmissionRegistry
  private lateinit var metadataRegistry: MetadataRegistry
  private lateinit var pluginRegistry: PluginRegistry
  private lateinit var vmRegistry: VMRegistry
  private var setupRegistry: SetupRegistry? = null

  private val setup = Setup(id = "test-setup", flavor = "myflavor",
      imageName = "myimage", availabilityZone = "my-az", blockDeviceSizeGb = 20,
      maxVMs = 10)

  @BeforeEach
  fun setUp(vertx: Vertx, ctx: VertxTestContext, info: TestInfo) {
    port = ServerSocket(0).use { it.localPort }

    // mock agent registry
    agentRegistry = mockk()
    mockkObject(AgentRegistryFactory)
    every { AgentRegistryFactory.create(any()) } returns agentRegistry

    // mock submission registry
    submissionRegistry = mockk()
    mockkObject(SubmissionRegistryFactory)
    every { SubmissionRegistryFactory.create(any()) } returns submissionRegistry
    coEvery { submissionRegistry.close() } just Runs

    // mock metadata registry
    metadataRegistry = mockk()
    mockkObject(MetadataRegistryFactory)
    every { MetadataRegistryFactory.create(any()) } returns metadataRegistry

    // mock plugin registry
    pluginRegistry = mockk()
    mockkObject(PluginRegistryFactory)
    every { PluginRegistryFactory.create() } returns pluginRegistry

    // mock VM registry
    vmRegistry = mockk()
    mockkObject(VMRegistryFactory)
    every { VMRegistryFactory.create(any()) } returns vmRegistry

    if (info.tags.contains(ENABLE_SETUPS)) {
      // mock setup registry
      setupRegistry = mockk()
      mockkObject(SetupRegistryFactory)
      every { SetupRegistryFactory.create(any(), any()) } returns setupRegistry!!
    }

    // deploy verticle under test
    val config = jsonObjectOf(
        ConfigConstants.HTTP_HOST to "localhost",
        ConfigConstants.HTTP_PORT to port,
        ConfigConstants.HTTP_POST_MAX_SIZE to maxPostSize
    )

    if (info.tags.contains(ENABLE_SETUPS)) {
      config.put(ConfigConstants.CLOUD_ENABLED, true)
    }

    val options = deploymentOptionsOf(config = config)
    vertx.deployVerticle(HttpEndpoint::class.qualifiedName, options,
      ctx.succeedingThenComplete())
  }

  @AfterEach
  fun tearDown() {
    unmockkAll()
  }

  /**
   * Check if the main entry point returns version information
   */
  @Test
  fun getVersion(vertx: Vertx, ctx: VertxTestContext) {
    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()
        assertThat(response.body().map).containsKey("version")
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if content negotiation works correctly
   */
  @Test
  fun contentNegotiation(vertx: Vertx, ctx: VertxTestContext) {
    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        // with "Accept: application/json"
        val response1 = client.get(port, "localhost", "/")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .putHeader("Accept", "application/json")
            .send()
            .await()
        assertThat(response1.body().map).containsKey("version")

        // with "Accept: text/html"
        val response2 = client.get(port, "localhost", "/")
            .`as`(BodyCodec.string())
            .expect(ResponsePredicate.SC_OK)
            .expect(contentType("text/html"))
            .putHeader("Accept", "text/html")
            .send()
            .await()
        assertThat(response2.body()).contains("html")

        // with "Accept: */*"
        val response3 = client.get(port, "localhost", "/")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .putHeader("Accept", "*/*")
            .send()
            .await()
        assertThat(response3.body().map).containsKey("version")

        // with complex "Accept" header
        val response4 = client.get(port, "localhost", "/")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .putHeader("Accept", "application/json,text/html")
            .send()
            .await()
        assertThat(response4.body().map).containsKey("version")

        // with complex "Accept" header
        val response5 = client.get(port, "localhost", "/")
            .`as`(BodyCodec.string())
            .expect(ResponsePredicate.SC_OK)
            .expect(contentType("text/html"))
            .putHeader("Accept", "text/html,application/json")
            .send()
            .await()
        assertThat(response5.body()).contains("html")

        // with complex "Accept" header
        val response6 = client.get(port, "localhost", "/")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .putHeader("Accept", "text/html;q=0.5,application/json;q=1.0")
            .send()
            .await()
        assertThat(response6.body().map).containsKey("version")

        // without "Accept" header
        val response7 = client.get(port, "localhost", "/")
            .expect(ResponsePredicate.SC_OK)
            .send()
            .await()
        assertThat(JsonObject(response7.body().toString(StandardCharsets.UTF_8)).map).containsKey("version")
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that only allowed routes are deployed
   */
  @Test
  fun allowSubsetOfRoutes(vertx: Vertx, ctx: VertxTestContext) {
    CoroutineScope(vertx.dispatcher()).launch {
      // stop original HTTP server because it has no custom routes allowed
      vertx.deploymentIDs().forEach { deploymentId ->
        awaitResult<Void> { vertx.undeploy(deploymentId, it) }
      }

      // start new HTTP server with only selected endpoints allowed
      val config = jsonObjectOf(
          ConfigConstants.HTTP_HOST to "localhost",
          ConfigConstants.HTTP_PORT to port,
          ConfigConstants.HTTP_POST_MAX_SIZE to maxPostSize,
          ConfigConstants.HTTP_ALLOW_ROUTES to "/|/services"
      )
      val options = deploymentOptionsOf(config = config)
      awaitResult {
        vertx.deployVerticle(HttpEndpoint::class.qualifiedName, options, it)
      }

      coEvery { metadataRegistry.findServices() } returns emptyList()

      val client = WebClient.create(vertx)
      ctx.coVerify {
        client.get(port, "localhost", "/")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()
        client.get(port, "localhost", "/services")
            .`as`(BodyCodec.jsonArray())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()
        client.get(port, "localhost", "/plugins")
            .expect(ResponsePredicate.SC_NOT_FOUND)
            .send()
            .await()
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that the endpoint returns a list of services
   */
  @Test
  fun getServices(vertx: Vertx, ctx: VertxTestContext) {
    val serviceMetadata = listOf(
        Service("ID", "Name", "Description", "/path", "other", listOf(
            ServiceParameter("ParamID", "ParamName", "ParamDesc",
                Argument.Type.INPUT, Cardinality(1, 1))
        ))
    )

    coEvery { metadataRegistry.findServices() } returns serviceMetadata

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/services")
            .`as`(BodyCodec.jsonArray())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()

        assertThat(JsonUtils.mapper.convertValue<List<Service>>(response.body().list))
            .isEqualTo(serviceMetadata)

        ctx.completeNow()
      }
    }
  }

  /**
   * Test that the endpoint returns a list a single service
   */
  @Test
  fun getServiceById(vertx: Vertx, ctx: VertxTestContext) {
    val serviceMetadata = listOf(
        Service("ID", "Name", "Description", "/path", "other", listOf(
            ServiceParameter("ParamID", "ParamName", "ParamDesc",
                Argument.Type.INPUT, Cardinality(1, 1))
        ))
    )

    coEvery { metadataRegistry.findServices() } returns serviceMetadata

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        client.get(port, "localhost", "/services/UNKNOWN_ID")
            .expect(ResponsePredicate.SC_NOT_FOUND)
            .send()
            .await()

        val response = client.get(port, "localhost", "/services/ID")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()

        assertThat(JsonUtils.fromJson<Service>(response.body()))
            .isEqualTo(serviceMetadata[0])

        ctx.completeNow()
      }
    }
  }

  /**
   * Test what happens if we request setups but cloud configuration is not
   * enabled
   */
  @Test
  fun getSetupsNoCloud(vertx: Vertx, ctx: VertxTestContext) {
    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val b1 = client.get(port, "localhost", "/setups")
            .`as`(BodyCodec.string())
            .expect(ResponsePredicate.SC_NOT_FOUND)
            .send()
            .await()
            .body()
        assertThat(b1).contains("cloud configuration is disabled")
        val b2 = client.get(port, "localhost", "/setups/foobar")
            .`as`(BodyCodec.string())
            .expect(ResponsePredicate.SC_NOT_FOUND)
            .send()
            .await()
            .body()
        assertThat(b2).contains("cloud configuration is disabled")
        ctx.completeNow()
      }
    }
  }

  /**
   * Test what happens if we request setups but cloud configuration is not
   * enabled
   */
  @Test
  @Tag(ENABLE_SETUPS)
  fun getSetupsEmpty(vertx: Vertx, ctx: VertxTestContext) {
    coEvery { setupRegistry!!.findSetups() } returns emptyList()

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val result = client.get(port, "localhost", "/setups")
            .`as`(BodyCodec.jsonArray())
            .expect(ResponsePredicate.SC_OK)
            .send()
            .await()
            .body()
        assertThat(result).isEmpty()

        client.get(port, "localhost", "/setups/foobar")
            .expect(ResponsePredicate.SC_NOT_FOUND)
            .send()
            .await()

        ctx.completeNow()
      }
    }
  }

  /**
   * Test that we can fetch a list of setups
   */
  @Test
  @Tag(ENABLE_SETUPS)
  fun getSetups(vertx: Vertx, ctx: VertxTestContext) {
    val s1 = Setup(
        id = "setup1",
        flavor = "mini",
        imageName = "ubuntu",
        availabilityZone = "az01",
        blockDeviceSizeGb = 50,
        maxVMs = 10
    )
    val s2 = Setup(
        id = "setup2",
        flavor = "large",
        imageName = "ubuntu",
        availabilityZone = "az02",
        blockDeviceSizeGb = 100,
        maxVMs = 100
    )
    val setups = listOf(s1, s2)

    coEvery { setupRegistry!!.findSetups() } returns setups

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val result = client.get(port, "localhost", "/setups")
            .`as`(BodyCodec.jsonArray())
            .expect(ResponsePredicate.SC_OK)
            .send()
            .await()
            .body()
        assertThat(result).isEqualTo(jsonArrayOf(
            jsonObjectOf(
                "id" to s1.id,
                "flavor" to s1.flavor,
                "imageName" to s1.imageName,
                "availabilityZone" to s1.availabilityZone,
                "blockDeviceSizeGb" to s1.blockDeviceSizeGb,
                "minVMs" to s1.minVMs,
                "maxVMs" to s1.maxVMs,
                "maxCreateConcurrent" to s1.maxCreateConcurrent,
                "provisioningScripts" to jsonArrayOf(),
                "providedCapabilities" to jsonArrayOf(),
                "additionalVolumes" to jsonArrayOf(),
                "parameters" to jsonObjectOf()
            ),
            jsonObjectOf(
                "id" to s2.id,
                "flavor" to s2.flavor,
                "imageName" to s2.imageName,
                "availabilityZone" to s2.availabilityZone,
                "blockDeviceSizeGb" to s2.blockDeviceSizeGb,
                "minVMs" to s2.minVMs,
                "maxVMs" to s2.maxVMs,
                "maxCreateConcurrent" to s2.maxCreateConcurrent,
                "provisioningScripts" to jsonArrayOf(),
                "providedCapabilities" to jsonArrayOf(),
                "additionalVolumes" to jsonArrayOf(),
                "parameters" to jsonObjectOf()
            )
        ))
        ctx.completeNow()
      }
    }
  }

  /**
   * Test that we can fetch a single setup by ID
   */
  @Test
  @Tag(ENABLE_SETUPS)
  fun getSetupById(vertx: Vertx, ctx: VertxTestContext) {
    val s1 = Setup(
        id = "setup1",
        flavor = "mini",
        imageName = "ubuntu",
        availabilityZone = "az01",
        blockDeviceSizeGb = 50,
        maxVMs = 10
    )
    val s2 = Setup(
        id = "setup2",
        flavor = "large",
        imageName = "ubuntu",
        availabilityZone = "az02",
        blockDeviceSizeGb = 100,
        maxVMs = 100
    )
    val setups = listOf(s1, s2)

    coEvery { setupRegistry!!.findSetups() } returns setups

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val result = client.get(port, "localhost", "/setups/${s1.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .send()
            .await()
            .body()
        assertThat(result).isEqualTo(jsonObjectOf(
            "id" to s1.id,
            "flavor" to s1.flavor,
            "imageName" to s1.imageName,
            "availabilityZone" to s1.availabilityZone,
            "blockDeviceSizeGb" to s1.blockDeviceSizeGb,
            "minVMs" to s1.minVMs,
            "maxVMs" to s1.maxVMs,
            "maxCreateConcurrent" to s1.maxCreateConcurrent,
            "provisioningScripts" to jsonArrayOf(),
            "providedCapabilities" to jsonArrayOf(),
            "additionalVolumes" to jsonArrayOf(),
            "parameters" to jsonObjectOf()
        ))

        client.get(port, "localhost", "/setups/foobar")
            .expect(ResponsePredicate.SC_NOT_FOUND)
            .send()
            .await()

        ctx.completeNow()
      }
    }
  }

  /**
   * Test that the endpoint returns a list of plugins
   */
  @Test
  fun getPlugins(vertx: Vertx, ctx: VertxTestContext) {
    val plugins = listOf(
      InitializerPlugin("InitializerPluginName", "/path", "1.0.0",
          listOf("fred", "foo", "bar")),
      OutputAdapterPlugin("OutputAdapterPluginName", "/path", "1.0.0", "dataType"),
      ProcessChainAdapterPlugin("Name", "/path", "1.0.0",
          listOf("fred", "foo", "bar")),
      ProcessChainConsistencyCheckerPlugin("ProcessChainAdapterPluginName",
          "/path", "1.0.0", listOf("fred", "foo", "bar")),
      ProgressEstimatorPlugin("ProgressEstimatorPluginName", "/path", "1.0.0",
          listOf("myService")),
      RuntimePlugin("RuntimePluginName", "/path", "1.0.0", "myRuntime"),
    )

    coEvery { pluginRegistry.getAllPlugins() } returns plugins

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/plugins")
          .`as`(BodyCodec.jsonArray())
          .expect(ResponsePredicate.SC_OK)
          .expect(ResponsePredicate.JSON)
          .send()
          .await()

        val returnedList = JsonUtils.mapper.convertValue<List<Plugin>>(response.body().list)
        assertThat(returnedList)
          .usingRecursiveFieldByFieldElementComparatorIgnoringFields("compiledFunction")
          .isEqualTo(plugins)

        ctx.completeNow()
      }
    }
  }

  /**
   * Test that the endpoint returns a single plugin
   */
  @Test
  fun getPluginByName(vertx: Vertx, ctx: VertxTestContext) {
    val plugins = listOf(
      InitializerPlugin("InitializerPluginName", "/path", "1.0.0",
          listOf("fred", "foo", "bar")),
    )

    coEvery { pluginRegistry.getAllPlugins() } returns plugins

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        client.get(port, "localhost", "/plugins/UNKNOWN_NAME")
          .expect(ResponsePredicate.SC_NOT_FOUND)
          .send()
          .await()

        val response = client.get(port, "localhost", "/plugins/InitializerPluginName")
          .`as`(BodyCodec.jsonObject())
          .expect(ResponsePredicate.SC_OK)
          .expect(ResponsePredicate.JSON)
          .send()
          .await()

        assertThat(JsonUtils.fromJson<Plugin>(response.body()))
          .usingRecursiveComparison()
          .ignoringFieldsMatchingRegexes("compiledFunction")
          .isEqualTo(plugins[0])

        ctx.completeNow()
      }
    }
  }

  /**
   * Test that the endpoint returns a list of workflows
   */
  @Test
  fun getWorkflows(vertx: Vertx, ctx: VertxTestContext) {
    val s1 = Submission(workflow = Workflow(), source = "actions: []")
    coEvery { submissionRegistry.countProcessChainsPerStatus(s1.id) } returns mapOf(
        ProcessChainStatus.REGISTERED to 1L,
        ProcessChainStatus.RUNNING to 2L,
        ProcessChainStatus.PAUSED to 6L,
        ProcessChainStatus.CANCELLED to 3L,
        ProcessChainStatus.ERROR to 4L,
        ProcessChainStatus.SUCCESS to 5L
    )

    val s2 = Submission(workflow = Workflow(), source = "actions: []")
    coEvery { submissionRegistry.countProcessChainsPerStatus(s2.id) } returns mapOf(
        ProcessChainStatus.REGISTERED to 11L,
        ProcessChainStatus.RUNNING to 12L,
        ProcessChainStatus.CANCELLED to 13L,
        ProcessChainStatus.ERROR to 14L,
        ProcessChainStatus.SUCCESS to 15L
    )

    val s3 = Submission(workflow = Workflow(priority = 10), status = Submission.Status.SUCCESS)
    coEvery { submissionRegistry.countProcessChainsPerStatus(s3.id) } returns
        mapOf(ProcessChainStatus.SUCCESS to 1L)

    coEvery { metadataRegistry.findServices() } returns emptyList()

    val js1 = JsonUtils.toJson(s1)
    js1.remove("workflow")
    js1.remove("source")
    val js2 = JsonUtils.toJson(s2)
    js2.remove("workflow")
    js2.remove("source")
    val js3 = JsonUtils.toJson(s3)
    js3.remove("workflow")
    js3.remove("source")

    coEvery { submissionRegistry.findSubmissionsRaw(any(), any(), any(), any(),
        excludeWorkflows = true, excludeSources = true) } returns listOf(js1, js2, js3)
    coEvery { submissionRegistry.countSubmissions() } returns 3

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/workflows")
            .`as`(BodyCodec.jsonArray())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()

        assertThat(response.headers()["x-page-size"]).isEqualTo("10")
        assertThat(response.headers()["x-page-offset"]).isEqualTo("0")
        assertThat(response.headers()["x-page-total"]).isEqualTo("3")

        assertThat(response.body()).isEqualTo(jsonArrayOf(
            jsonObjectOf(
                "id" to s1.id,
                "status" to Submission.Status.ACCEPTED.toString(),
                "runningProcessChains" to 2,
                "pausedProcessChains" to 6,
                "cancelledProcessChains" to 3,
                "failedProcessChains" to 4,
                "succeededProcessChains" to 5,
                "totalProcessChains" to 21,
                "requiredCapabilities" to jsonArrayOf()
            ),
            jsonObjectOf(
                "id" to s2.id,
                "status" to Submission.Status.ACCEPTED.toString(),
                "runningProcessChains" to 12,
                "pausedProcessChains" to 0,
                "cancelledProcessChains" to 13,
                "failedProcessChains" to 14,
                "succeededProcessChains" to 15,
                "totalProcessChains" to 65,
                "requiredCapabilities" to jsonArrayOf()
            ),
            jsonObjectOf(
                "id" to s3.id,
                "status" to Submission.Status.SUCCESS.toString(),
                "priority" to 10,
                "runningProcessChains" to 0,
                "pausedProcessChains" to 0,
                "cancelledProcessChains" to 0,
                "failedProcessChains" to 0,
                "succeededProcessChains" to 1,
                "totalProcessChains" to 1,
                "requiredCapabilities" to jsonArrayOf()
            )
        ))
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that the endpoint returns a list of workflows with a given status
   */
  @Test
  fun getWorkflowsByStatus(vertx: Vertx, ctx: VertxTestContext) {
    val s3 = Submission(workflow = Workflow(), status = Submission.Status.SUCCESS,
        source = "actions: []")
    val js3 = JsonUtils.toJson(s3)
    js3.remove("workflow")
    js3.remove("source")

    coEvery { submissionRegistry.countProcessChainsPerStatus(s3.id) } returns
        mapOf(ProcessChainStatus.SUCCESS to 1L)

    coEvery { metadataRegistry.findServices() } returns emptyList()

    coEvery { submissionRegistry.findSubmissionsRaw(Submission.Status.SUCCESS,
        any(), any(), any(), excludeWorkflows = true, excludeSources = true) } returns listOf(js3)
    coEvery { submissionRegistry.countSubmissions(Submission.Status.SUCCESS) } returns 1

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/workflows?status=SUCCESS")
            .`as`(BodyCodec.jsonArray())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()

        assertThat(response.headers()["x-page-size"]).isEqualTo("10")
        assertThat(response.headers()["x-page-offset"]).isEqualTo("0")
        assertThat(response.headers()["x-page-total"]).isEqualTo("1")

        assertThat(response.body()).isEqualTo(jsonArrayOf(
            jsonObjectOf(
                "id" to s3.id,
                "status" to Submission.Status.SUCCESS.toString(),
                "runningProcessChains" to 0,
                "pausedProcessChains" to 0,
                "cancelledProcessChains" to 0,
                "failedProcessChains" to 0,
                "succeededProcessChains" to 1,
                "totalProcessChains" to 1,
                "requiredCapabilities" to jsonArrayOf()
            )
        ))
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that the endpoint returns a single workflow
   */
  @Test
  fun getWorkflowById(vertx: Vertx, ctx: VertxTestContext) {
    val source = "actions: []"
    val s1 = Submission(workflow = Workflow(priority = -10), source = source)
    coEvery { submissionRegistry.countProcessChainsPerStatus(s1.id) } returns mapOf(
        ProcessChainStatus.REGISTERED to 1L,
        ProcessChainStatus.RUNNING to 2L,
        ProcessChainStatus.PAUSED to 6L,
        ProcessChainStatus.CANCELLED to 3L,
        ProcessChainStatus.ERROR to 4L,
        ProcessChainStatus.SUCCESS to 5L
    )

    coEvery { metadataRegistry.findServices() } returns emptyList()

    coEvery { submissionRegistry.findSubmissionById(s1.id) } returns s1
    coEvery { submissionRegistry.findSubmissionById(neq(s1.id)) } returns null

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        client.get(port, "localhost", "/workflows/${s1.id}_doesnotexist")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_NOT_FOUND)
            .send()
            .await()

        val response = client.get(port, "localhost", "/workflows/${s1.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()

        assertThat(response.body()).isEqualTo(jsonObjectOf(
            "id" to s1.id,
            "workflow" to JsonUtils.toJson(s1.workflow),
            "status" to Submission.Status.ACCEPTED.toString(),
            "priority" to -10,
            "runningProcessChains" to 2,
            "pausedProcessChains" to 6,
            "cancelledProcessChains" to 3,
            "failedProcessChains" to 4,
            "succeededProcessChains" to 5,
            "totalProcessChains" to 21,
            "requiredCapabilities" to jsonArrayOf(),
            "source" to source
        ))
      }

      ctx.completeNow()
    }
  }

  /**
   * Test that the endpoint returns a single workflow with required capabilities
   */
  @Test
  fun getWorkflowByIdRequiredCapabilities(vertx: Vertx, ctx: VertxTestContext) {
    val s1 = Submission(workflow = Workflow(actions = listOf(
        ExecuteAction(service = "a"),
        ForEachAction(actions = listOf(
            ExecuteAction(service = "b")
        ), input = Variable(), enumerator = Variable()),
    )), requiredCapabilities = setOf("cap1", "cap2", "cap3"))
    coEvery { submissionRegistry.countProcessChainsPerStatus(s1.id) } returns
        mapOf(ProcessChainStatus.REGISTERED to 1L)
    coEvery { submissionRegistry.findSubmissionById(s1.id) } returns s1

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/workflows/${s1.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()

        assertThat(response.body()).isEqualTo(jsonObjectOf(
            "id" to s1.id,
            "workflow" to JsonUtils.toJson(s1.workflow),
            "status" to Submission.Status.ACCEPTED.toString(),
            "runningProcessChains" to 0,
            "pausedProcessChains" to 0,
            "cancelledProcessChains" to 0,
            "failedProcessChains" to 0,
            "succeededProcessChains" to 0,
            "totalProcessChains" to 1,
            "requiredCapabilities" to jsonArrayOf("cap1", "cap2", "cap3")
        ))
      }

      ctx.completeNow()
    }
  }

  /**
   * Test that the endpoint returns a single successful workflow with a result
   */
  @Test
  fun getWorkflowByIdSuccess(vertx: Vertx, ctx: VertxTestContext) {
    val s1 = Submission(workflow = Workflow(), status = Submission.Status.SUCCESS)
    coEvery { submissionRegistry.countProcessChainsPerStatus(s1.id) } returns
        mapOf(ProcessChainStatus.SUCCESS to 1L)

    coEvery { metadataRegistry.findServices() } returns emptyList()

    coEvery { submissionRegistry.findSubmissionById(s1.id) } returns s1

    val results = mapOf("output_file1" to listOf("/out/test.txt"))
    coEvery { submissionRegistry.getSubmissionResults(s1.id) } returns results

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/workflows/${s1.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()

        assertThat(response.body()).isEqualTo(jsonObjectOf(
            "id" to s1.id,
            "workflow" to JsonUtils.toJson(s1.workflow),
            "status" to Submission.Status.SUCCESS.toString(),
            "runningProcessChains" to 0,
            "pausedProcessChains" to 0,
            "cancelledProcessChains" to 0,
            "failedProcessChains" to 0,
            "succeededProcessChains" to 1,
            "totalProcessChains" to 1,
            "results" to JsonUtils.toJson(results),
            "requiredCapabilities" to jsonArrayOf()
        ))
      }

      ctx.completeNow()
    }
  }

  /**
   * Test that the endpoint returns a single successful workflow with an error message
   */
  @Test
  fun getWorkflowByIdError(vertx: Vertx, ctx: VertxTestContext) {
    val s1 = Submission(workflow = Workflow(), status = Submission.Status.ERROR)
    coEvery { submissionRegistry.countProcessChainsPerStatus(s1.id) } returns
        mapOf(ProcessChainStatus.ERROR to 1)

    coEvery { metadataRegistry.findServices() } returns emptyList()

    coEvery { submissionRegistry.findSubmissionById(s1.id) } returns s1

    val errorMessage = "This is an error message"
    coEvery { submissionRegistry.getSubmissionErrorMessage(s1.id) } returns errorMessage

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/workflows/${s1.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()

        assertThat(response.body()).isEqualTo(jsonObjectOf(
            "id" to s1.id,
            "workflow" to JsonUtils.toJson(s1.workflow),
            "status" to Submission.Status.ERROR.toString(),
            "runningProcessChains" to 0,
            "pausedProcessChains" to 0,
            "cancelledProcessChains" to 0,
            "failedProcessChains" to 1,
            "succeededProcessChains" to 0,
            "totalProcessChains" to 1,
            "errorMessage" to errorMessage,
            "requiredCapabilities" to jsonArrayOf()
        ))
      }

      ctx.completeNow()
    }
  }

  /**
   * Test if a workflow can be updated
   */
  @Test
  fun putWorkflowById(vertx: Vertx, ctx: VertxTestContext) {
    val source = "actions: []"
    val s1 = Submission(workflow = Workflow(), status = Submission.Status.RUNNING,
        source = source)

    coEvery { submissionRegistry.findSubmissionById(s1.id) } returns s1
    coEvery { submissionRegistry.findSubmissionById("UNKNOWN") } returns null
    coEvery { submissionRegistry.findProcessChainIdsBySubmissionIdAndStatus(
        s1.id, ProcessChainStatus.RUNNING) } returns emptyList()
    coEvery { submissionRegistry.getSubmissionStatus(s1.id) } returns
        Submission.Status.RUNNING andThen Submission.Status.CANCELLED

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val cancelledBody = jsonObjectOf(
            "status" to "CANCELLED"
        )

        val expectedPriority = 100
        val priorityBody = jsonObjectOf(
            "priority" to expectedPriority
        )

        val missingBody = jsonObjectOf(
            "foo" to "bar"
        )

        val invalidBody1 = jsonObjectOf(
            "status" to "INVALID"
        )

        val invalidBody2 = jsonObjectOf(
            "status" to 5
        )

        val invalidBody3 = jsonObjectOf(
            "priority" to "INVALID"
        )

        // test invalid requests
        client.put(port, "localhost", "/workflows/${s1.id}")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .send()
            .await()

        client.put(port, "localhost", "/workflows/${s1.id}")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .sendBuffer(Buffer.buffer("INVALID BODY"))
            .await()

        client.put(port, "localhost", "/workflows/${s1.id}")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .sendJsonObject(missingBody)
            .await()

        client.put(port, "localhost", "/workflows/${s1.id}")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .sendJsonObject(invalidBody1)
            .await()

        client.put(port, "localhost", "/workflows/${s1.id}")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .sendJsonObject(invalidBody2)
            .await()

        client.put(port, "localhost", "/workflows/${s1.id}")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .sendJsonObject(invalidBody3)
            .await()

        client.put(port, "localhost", "/workflows/UNKNOWN")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_NOT_FOUND)
            .sendJsonObject(cancelledBody)
            .await()

        client.put(port, "localhost", "/workflows/${s1.id}")
            .`as`(BodyCodec.none())
            .putHeader("accept", "text/html")
            .expect(ResponsePredicate.SC_NOT_ACCEPTABLE)
            .sendJsonObject(cancelledBody)
            .await()

        // now test valid requests (cancel submission)
        coEvery { submissionRegistry.setAllProcessChainsStatus(s1.id,
            ProcessChainStatus.REGISTERED, ProcessChainStatus.CANCELLED) } just Runs
        coEvery { submissionRegistry.setAllProcessChainsStatus(s1.id,
            ProcessChainStatus.PAUSED, ProcessChainStatus.CANCELLED) } just Runs
        coEvery { submissionRegistry.countProcessChainsPerStatus(s1.id) } returns mapOf(
            ProcessChainStatus.RUNNING to 1L,
            ProcessChainStatus.CANCELLED to 2L
        )
        val response1 = client.put(port, "localhost", "/workflows/${s1.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .sendJsonObject(cancelledBody)
            .await()

        assertThat(response1.body()).isEqualTo(jsonObjectOf(
            "id" to s1.id,
            "status" to Submission.Status.RUNNING.toString(),
            "requiredCapabilities" to jsonArrayOf(),
            "source" to source,
            "runningProcessChains" to 1,
            "pausedProcessChains" to 0,
            "cancelledProcessChains" to 2,
            "succeededProcessChains" to 0,
            "failedProcessChains" to 0,
            "totalProcessChains" to 3
        ))

        coVerify(exactly = 1) {
          submissionRegistry.setAllProcessChainsStatus(s1.id,
              ProcessChainStatus.REGISTERED, ProcessChainStatus.CANCELLED)
          submissionRegistry.setAllProcessChainsStatus(s1.id,
              ProcessChainStatus.PAUSED, ProcessChainStatus.CANCELLED)
        }

        // set priority
        coEvery { submissionRegistry.setSubmissionPriority(s1.id, expectedPriority) } returns true
        coEvery { submissionRegistry.setAllProcessChainsPriority(s1.id, expectedPriority) } just Runs
        coEvery { submissionRegistry.findSubmissionById(s1.id) } returns s1.copy(priority = expectedPriority)
        val response2 = client.put(port, "localhost", "/workflows/${s1.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .sendJsonObject(priorityBody)
            .await()

        assertThat(response2.body()).isEqualTo(jsonObjectOf(
            "id" to s1.id,
            "status" to Submission.Status.RUNNING.toString(),
            "requiredCapabilities" to jsonArrayOf(),
            "source" to source,
            "runningProcessChains" to 1,
            "pausedProcessChains" to 0,
            "cancelledProcessChains" to 2,
            "succeededProcessChains" to 0,
            "failedProcessChains" to 0,
            "totalProcessChains" to 3,
            "priority" to expectedPriority
        ))

        coVerify(exactly = 1) {
          submissionRegistry.setSubmissionPriority(s1.id, expectedPriority)
          submissionRegistry.setAllProcessChainsPriority(s1.id, expectedPriority)
        }

        // try to change priority of finished submission
        coEvery { submissionRegistry.findSubmissionById(s1.id) } returns s1.copy(
            status = Submission.Status.SUCCESS
        )
        client.put(port, "localhost", "/workflows/${s1.id}")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_UNPROCESSABLE_ENTITY)
            .sendJsonObject(priorityBody)
            .await()
      }

      ctx.completeNow()
    }
  }

  /**
   * Test that the endpoint rejects an empty workflow
   */
  @Test
  fun postWorkflowEmpty(vertx: Vertx, ctx: VertxTestContext) {
    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        client.post(port, "localhost", "/workflows")
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .send()
            .await()
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that the endpoint rejects an invalid workflow
   */
  @Test
  fun postWorkflowInvalid(vertx: Vertx, ctx: VertxTestContext) {
    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        client.post(port, "localhost", "/workflows")
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .sendJsonObject(JsonObject().put("invalid", true))
            .await()
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that the endpoint rejects a workflow that is too large
   */
  @Test
  fun postWorkflowTooLarge(vertx: Vertx, ctx: VertxTestContext) {
    val w = Workflow(name = "a".repeat(maxPostSize))

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        client.post(port, "localhost", "/workflows")
            .expect(ResponsePredicate.SC_REQUEST_ENTITY_TOO_LARGE)
            .sendJsonObject(JsonUtils.toJson(w))
            .await()
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that the endpoint rejects a workflow with a wrong model version
   */
  @Test
  fun postWorkflowWrongVersion(vertx: Vertx, ctx: VertxTestContext) {
    val w = Workflow(api = "0.0.0")

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        client.post(port, "localhost", "/workflows")
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .sendJsonObject(JsonUtils.toJson(w))
            .await()
      }
      ctx.completeNow()
    }
  }

  private fun doPostWorkflow(vertx: Vertx, ctx: VertxTestContext, body: Buffer,
      expectedWorkflow: Workflow, expectedRequiredCapabilities: List<String> = emptyList()) {
    val submissionSlot = slot<Submission>()
    coEvery { submissionRegistry.addSubmission(capture(submissionSlot)) } answers {
      ctx.verify {
        assertThat(submissionSlot.captured.id).isNotNull
        assertThat(submissionSlot.captured.status).isEqualTo(Submission.Status.ACCEPTED)
        assertThat(submissionSlot.captured.workflow).isEqualTo(expectedWorkflow)
      }
    }

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val response = client.post(port, "localhost", "/workflows")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_ACCEPTED)
            .expect(ResponsePredicate.JSON)
            .sendBuffer(body)
            .await()

        assertThat(response.body()).isEqualTo(jsonObjectOf(
            "id" to submissionSlot.captured.id,
            "workflow" to JsonUtils.toJson(expectedWorkflow),
            "status" to Submission.Status.ACCEPTED.toString(),
            "requiredCapabilities" to JsonArray(expectedRequiredCapabilities),
            "source" to body.toString()
        ))
      }

      ctx.completeNow()
    }
  }

  /**
   * Test that a workflow can be successfully posted
   */
  @Test
  fun postWorkflow(vertx: Vertx, ctx: VertxTestContext) {
    coEvery { metadataRegistry.findServices() } returns emptyList()
    val expected = Workflow()
    val buf = Buffer.buffer(JsonUtils.writeValueAsString(expected))
    doPostWorkflow(vertx, ctx, buf, expected)
  }

  /**
   * Test that a workflow can be successfully posted as YAML
   */
  @Test
  fun postWorkflowYaml(vertx: Vertx, ctx: VertxTestContext) {
    coEvery { metadataRegistry.findServices() } returns emptyList()
    val expected = Workflow()
    val buf = Buffer.buffer(YamlUtils.mapper.writeValueAsString(expected))
    doPostWorkflow(vertx, ctx, buf, expected)
  }

  /**
   * Test that required capabilities are calculated correctly when a
   * submission is added to the
   */
  @Test
  fun postWorkflowYamlRequiredCapabilities(vertx: Vertx, ctx: VertxTestContext) {
    coEvery { metadataRegistry.findServices() } returns listOf(
        Service(id = "a", name = "name a", description = "", path = "",
            runtime = "", parameters = emptyList(),
            requiredCapabilities = setOf("cap1", "cap2")),
        Service(id = "b", name = "name b", description = "", path = "",
            runtime = "", parameters = emptyList(),
            requiredCapabilities = setOf("cap1", "cap3"))
    )

    val expected = Workflow(actions = listOf(
        ExecuteAction(service = "a"),
        ForEachAction(actions = listOf(
            ExecuteAction(service = "b")
        ), input = Variable(value = "foobar"), enumerator = Variable()),
    ))

    val buf = Buffer.buffer(YamlUtils.mapper.writeValueAsString(expected))
    doPostWorkflow(vertx, ctx, buf, expected, listOf("cap1", "cap2", "cap3"))
  }

  /**
   * Test that the endpoint returns a list of all process chains (without executables)
   */
  @Test
  fun getProcessChains(vertx: Vertx, ctx: VertxTestContext) {
    val s1 = Submission(workflow = Workflow())
    val s2 = Submission(workflow = Workflow())
    val js1 = JsonUtils.toJson(s1)
    val js2 = JsonUtils.toJson(s2)
    coEvery { submissionRegistry.findSubmissionsRaw() } returns listOf(js1, js2)

    val pc1 = ProcessChain(executables = listOf(Executable(path = "path",
        serviceId = "foobar", arguments = emptyList())))
    val pc2 = ProcessChain()
    val pc3 = ProcessChain()
    val pc4 = ProcessChain()
    val pc5 = ProcessChain()

    coEvery { submissionRegistry.findProcessChains(any(), any(), any(), any(), any(), true) } returns listOf(
        Pair(pc1, s1.id), Pair(pc2, s1.id), Pair(pc3, s2.id), Pair(pc4, s2.id), Pair(pc5, s2.id))
    coEvery { submissionRegistry.countProcessChains() } returns 5

    coEvery { submissionRegistry.getProcessChainStatus(pc1.id) } returns ProcessChainStatus.SUCCESS
    coEvery { submissionRegistry.getProcessChainStatus(pc2.id) } returns ProcessChainStatus.RUNNING
    coEvery { submissionRegistry.getProcessChainStatus(pc3.id) } returns ProcessChainStatus.REGISTERED
    coEvery { submissionRegistry.getProcessChainStatus(pc4.id) } returns ProcessChainStatus.ERROR
    coEvery { submissionRegistry.getProcessChainStatus(pc5.id) } returns ProcessChainStatus.PAUSED

    val agentId = UniqueID.next()
    val startTime = Instant.now()
    val endTime = Instant.now().plusMillis(1234)
    coEvery { submissionRegistry.getLastProcessChainRun(pc1.id) } returns
        Run(agentId, startTime, endTime, ProcessChainStatus.SUCCESS,
            "This error SHOULD NOT BE returned")
    coEvery { submissionRegistry.getLastProcessChainRun(pc2.id) } returns
        Run(agentId, startTime)
    coEvery { submissionRegistry.getLastProcessChainRun(pc3.id) } returns null
    coEvery { submissionRegistry.getLastProcessChainRun(pc4.id) } returns
        Run(agentId, startTime, endTime, ProcessChainStatus.ERROR,
            "THIS is an ERROR")
    coEvery { submissionRegistry.getLastProcessChainRun(pc5.id) } returns
        Run(agentId, startTime, endTime, ProcessChainStatus.ERROR,
            "Waiting for retry")

    coEvery { submissionRegistry.getProcessChainResults(pc1.id) } returns mapOf(
        "output_file1" to listOf("output.txt"))
    coEvery { submissionRegistry.getProcessChainResults(pc2.id) } returns mapOf(
        "output_file_that_should_not_be_returned" to listOf("output2.txt"))
    coEvery { submissionRegistry.getProcessChainResults(pc3.id) } returns null
    coEvery { submissionRegistry.getProcessChainResults(pc4.id) } returns null
    coEvery { submissionRegistry.getProcessChainResults(pc5.id) } returns null

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/processchains")
            .`as`(BodyCodec.jsonArray())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()

        assertThat(response.headers()["x-page-size"]).isEqualTo("10")
        assertThat(response.headers()["x-page-offset"]).isEqualTo("0")
        assertThat(response.headers()["x-page-total"]).isEqualTo("5")

        assertThat(response.body()).isEqualTo(jsonArrayOf(
            jsonObjectOf(
                "id" to pc1.id,
                "requiredCapabilities" to jsonArrayOf(),
                "submissionId" to s1.id,
                "status" to "SUCCESS",
                "agentId" to agentId,
                "startTime" to startTime,
                "endTime" to endTime
            ),
            jsonObjectOf(
                "id" to pc2.id,
                "requiredCapabilities" to jsonArrayOf(),
                "submissionId" to s1.id,
                "status" to "RUNNING",
                "agentId" to agentId,
                "startTime" to startTime
            ),
            jsonObjectOf(
                "id" to pc3.id,
                "requiredCapabilities" to jsonArrayOf(),
                "submissionId" to s2.id,
                "status" to "REGISTERED"
            ),
            jsonObjectOf(
                "id" to pc4.id,
                "requiredCapabilities" to jsonArrayOf(),
                "submissionId" to s2.id,
                "status" to "ERROR",
                "agentId" to agentId,
                "startTime" to startTime,
                "endTime" to endTime,
                "errorMessage" to "THIS is an ERROR"
            ),
            jsonObjectOf(
                "id" to pc5.id,
                "requiredCapabilities" to jsonArrayOf(),
                "submissionId" to s2.id,
                "status" to "PAUSED"
            )
        ))
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that the endpoint returns a list of process chains (without
   * executables) for a given submission ID
   */
  @Test
  fun getProcessChainsBySubmissionId(vertx: Vertx, ctx: VertxTestContext) {
    val s1 = Submission(workflow = Workflow())
    val s2 = Submission(workflow = Workflow())
    val js1 = JsonUtils.toJson(s1)
    val js2 = JsonUtils.toJson(s2)
    coEvery { submissionRegistry.findSubmissionsRaw() } returns listOf(js1, js2)

    val pc1 = ProcessChain(executables = listOf(Executable(path = "path",
        serviceId = "foobar", arguments = emptyList())))
    val pc2 = ProcessChain()

    coEvery { submissionRegistry.findProcessChains(s1.id, null, 10, 0, -1, true) } returns
        listOf(pc1 to s1.id, pc2 to s1.id)
    coEvery { submissionRegistry.countProcessChains(s1.id) } returns 2

    coEvery { submissionRegistry.getProcessChainStatus(pc1.id) } returns ProcessChainStatus.SUCCESS
    coEvery { submissionRegistry.getProcessChainStatus(pc2.id) } returns ProcessChainStatus.RUNNING

    val agentId = UniqueID.next()
    val startTime = Instant.now()
    val endTime = Instant.now().plusMillis(1234)
    coEvery { submissionRegistry.getLastProcessChainRun(pc1.id) } returns
        Run(agentId, startTime, endTime)
    coEvery { submissionRegistry.getLastProcessChainRun(pc2.id) } returns
        Run(agentId, startTime)

    coEvery { submissionRegistry.getProcessChainResults(pc1.id) } returns mapOf(
        "output_file1" to listOf("output.txt"))
    coEvery { submissionRegistry.getProcessChainResults(pc2.id) } returns null

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/processchains?submissionId=${s1.id}")
            .`as`(BodyCodec.jsonArray())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()

        assertThat(response.headers()["x-page-size"]).isEqualTo("10")
        assertThat(response.headers()["x-page-offset"]).isEqualTo("0")
        assertThat(response.headers()["x-page-total"]).isEqualTo("2")

        assertThat(response.body()).isEqualTo(jsonArrayOf(
            jsonObjectOf(
                "id" to pc1.id,
                "requiredCapabilities" to jsonArrayOf(),
                "submissionId" to s1.id,
                "status" to "SUCCESS",
                "agentId" to agentId,
                "startTime" to startTime,
                "endTime" to endTime
            ),
            jsonObjectOf(
                "id" to pc2.id,
                "requiredCapabilities" to jsonArrayOf(),
                "submissionId" to s1.id,
                "status" to "RUNNING",
                "agentId" to agentId,
                "startTime" to startTime
            )
        ))
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that the endpoint returns a list of process chains (without
   * executables) for a given submission ID and status
   */
  @Test
  fun getProcessChainsBySubmissionIdAndStatus(vertx: Vertx, ctx: VertxTestContext) {
    val s1 = Submission(workflow = Workflow())
    val s2 = Submission(workflow = Workflow())
    val js1 = JsonUtils.toJson(s1)
    val js2 = JsonUtils.toJson(s2)
    coEvery { submissionRegistry.findSubmissionsRaw() } returns listOf(js1, js2)

    val pc1 = ProcessChain(executables = listOf(Executable(path = "path",
        serviceId = "foobar", arguments = emptyList())))

    coEvery { submissionRegistry.findProcessChains(s1.id, ProcessChainStatus.SUCCESS,
        10, 0, -1, true) } returns listOf(pc1 to s1.id)
    coEvery { submissionRegistry.countProcessChains(s1.id, ProcessChainStatus.SUCCESS) } returns 1

    coEvery { submissionRegistry.getProcessChainStatus(pc1.id) } returns ProcessChainStatus.SUCCESS

    val agentId = UniqueID.next()
    val startTime = Instant.now()
    val endTime = Instant.now().plusMillis(1234)
    coEvery { submissionRegistry.getLastProcessChainRun(pc1.id) } returns
        Run(agentId, startTime, endTime)

    coEvery { submissionRegistry.getProcessChainResults(pc1.id) } returns mapOf(
        "output_file1" to listOf("output.txt"))

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val response = client.get(port, "localhost",
            "/processchains?submissionId=${s1.id}&status=SUCCESS")
            .`as`(BodyCodec.jsonArray())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()

        assertThat(response.headers()["x-page-size"]).isEqualTo("10")
        assertThat(response.headers()["x-page-offset"]).isEqualTo("0")
        assertThat(response.headers()["x-page-total"]).isEqualTo("1")

        assertThat(response.body()).isEqualTo(jsonArrayOf(
            jsonObjectOf(
                "id" to pc1.id,
                "requiredCapabilities" to jsonArrayOf(),
                "submissionId" to s1.id,
                "status" to "SUCCESS",
                "agentId" to agentId,
                "startTime" to startTime,
                "endTime" to endTime
            )
        ))
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that the endpoint returns a single process chain
   */
  @Test
  fun getProcessChainById(vertx: Vertx, ctx: VertxTestContext) {
    val eid = UniqueID.next()
    val sid = UniqueID.next()
    val pc1 = ProcessChain(executables = listOf(Executable(id = eid,
        path = "path", serviceId = "foobar", arguments = emptyList())))

    coEvery { submissionRegistry.findProcessChainById(pc1.id) } returns pc1
    coEvery { submissionRegistry.findProcessChainById(neq(pc1.id)) } returns null
    coEvery { submissionRegistry.getProcessChainSubmissionId(pc1.id) } returns sid
    coEvery { submissionRegistry.getProcessChainStatus(pc1.id) } returns
        ProcessChainStatus.SUCCESS
    coEvery { submissionRegistry.countProcessChainRuns(pc1.id) } returns 3
    val agentId = UniqueID.next()
    val startTime = Instant.now()
    val endTime = Instant.now().plusMillis(4321)
    coEvery { submissionRegistry.getLastProcessChainRun(pc1.id) } returns
        Run(agentId, startTime, endTime)
    coEvery { submissionRegistry.getProcessChainResults(pc1.id) } returns mapOf(
        "output_file1" to listOf("output.txt"))

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        client.get(port, "localhost", "/processchains/${pc1.id}_doesnotexist")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_NOT_FOUND)
            .send()
            .await()

        val response = client.get(port, "localhost", "/processchains/${pc1.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()

        assertThat(response.body()).isEqualTo(jsonObjectOf(
            "id" to pc1.id,
            "executables" to jsonArrayOf(
                jsonObjectOf(
                    "id" to eid,
                    "path" to "path",
                    "serviceId" to "foobar",
                    "arguments" to jsonArrayOf(),
                    "runtime" to "other",
                    "runtimeArgs" to jsonArrayOf()
                )
            ),
            "requiredCapabilities" to jsonArrayOf(),
            "submissionId" to sid,
            "status" to ProcessChainStatus.SUCCESS.toString(),
            "agentId" to agentId,
            "startTime" to startTime,
            "endTime" to endTime,
            "totalRuns" to 3,
            "runNumber" to 3,
            "results" to jsonObjectOf(
                "output_file1" to jsonArrayOf("output.txt")
            )
        ))
      }

      ctx.completeNow()
    }
  }

  /**
   * Test that the endpoint returns a single process chain with information
   * about estimated progress
   */
  @Test
  fun getProcessChainByIdProgress(vertx: Vertx, ctx: VertxTestContext) {
    val expectedProgress = 0.55
    val eid = UniqueID.next()
    val sid = UniqueID.next()
    val pc1 = ProcessChain(executables = listOf(Executable(id = eid,
        path = "path", serviceId = "foobar", arguments = emptyList())))

    coEvery { submissionRegistry.findProcessChainById(pc1.id) } returns pc1
    coEvery { submissionRegistry.findProcessChainById(neq(pc1.id)) } returns null
    coEvery { submissionRegistry.getProcessChainSubmissionId(pc1.id) } returns sid
    coEvery { submissionRegistry.getProcessChainStatus(pc1.id) } returns
        ProcessChainStatus.RUNNING
    coEvery { submissionRegistry.countProcessChainRuns(pc1.id) } returns 1
    val agentId = UniqueID.next()
    val startTime = Instant.now()
    coEvery { submissionRegistry.getLastProcessChainRun(pc1.id) } returns
        Run(agentId, startTime)

    val address = LOCAL_AGENT_ADDRESS_PREFIX + pc1.id
    vertx.eventBus().consumer<JsonObject>(address).handler { msg ->
      if (msg.body().getString("action") == "getProgress") {
        msg.reply(expectedProgress)
      }
    }

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/processchains/${pc1.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()

        assertThat(response.body()).isEqualTo(jsonObjectOf(
            "id" to pc1.id,
            "executables" to jsonArrayOf(
                jsonObjectOf(
                    "id" to eid,
                    "path" to "path",
                    "serviceId" to "foobar",
                    "arguments" to jsonArrayOf(),
                    "runtime" to "other",
                    "runtimeArgs" to jsonArrayOf()
                )
            ),
            "requiredCapabilities" to jsonArrayOf(),
            "submissionId" to sid,
            "status" to ProcessChainStatus.RUNNING.toString(),
            "agentId" to agentId,
            "startTime" to startTime,
            "totalRuns" to 1,
            "runNumber" to 1,
            "estimatedProgress" to expectedProgress
        ))
      }

      ctx.completeNow()
    }
  }

  private fun doGetProcessChainRun(vertx: Vertx, ctx: VertxTestContext, running: Boolean = false) {
    val eid = UniqueID.next()
    val sid = UniqueID.next()
    val pc1 = ProcessChain(executables = listOf(Executable(id = eid,
        path = "path", serviceId = "foobar", arguments = emptyList())))

    val agentId1 = UniqueID.next()
    val agentId2 = UniqueID.next()
    val startTime1 = Instant.now().minusMillis(6000)
    val endTime1 = Instant.now().minusMillis(5000)
    val startTime2 = Instant.now().minusMillis(4000)
    val endTime2 = Instant.now().minusMillis(3000)
    val error1 = "run1"
    val run1 = Run(agentId1, startTime1, endTime1, ProcessChainStatus.ERROR,
        error1, autoResumeAfter = startTime2)
    val run2 = if (running) {
      Run(agentId2, startTime2)
    } else {
      Run(agentId2, startTime2, endTime2, ProcessChainStatus.SUCCESS)
    }

    coEvery { submissionRegistry.findProcessChainById(pc1.id) } returns pc1
    coEvery { submissionRegistry.findProcessChainById(neq(pc1.id)) } returns null
    coEvery { submissionRegistry.getProcessChainSubmissionId(pc1.id) } returns sid
    coEvery { submissionRegistry.getProcessChainStatus(pc1.id) } returns
        if (running) ProcessChainStatus.RUNNING else ProcessChainStatus.SUCCESS
    coEvery { submissionRegistry.countProcessChainRuns(pc1.id) } returns 2
    coEvery { submissionRegistry.getProcessChainRun(pc1.id, -1) } returns null
    coEvery { submissionRegistry.getProcessChainRun(pc1.id, 0) } returns null
    coEvery { submissionRegistry.getProcessChainRun(pc1.id, 1) } returns run1
    coEvery { submissionRegistry.getProcessChainRun(pc1.id, 2) } returns run2
    coEvery { submissionRegistry.getProcessChainRun(pc1.id, 3) } returns null
    coEvery { submissionRegistry.getLastProcessChainRun(pc1.id) } returns run2
    coEvery { submissionRegistry.getProcessChainResults(pc1.id) } returns mapOf(
        "output_file1" to listOf("output.txt"))

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        client.get(port, "localhost", "/processchains/${pc1.id}_doesnotexist/runs/1")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_NOT_FOUND)
            .send()
            .await()
      }

      ctx.coVerify {
        val r = client.get(port, "localhost", "/processchains/${pc1.id}/runs/-1")
            .`as`(BodyCodec.string())
            .expect(ResponsePredicate.SC_NOT_FOUND)
            .send()
            .await()
            .body()
        assertThat(r).isEqualTo("There is no run -1")
      }

      ctx.coVerify {
        val r = client.get(port, "localhost", "/processchains/${pc1.id}/runs/0")
            .`as`(BodyCodec.string())
            .expect(ResponsePredicate.SC_NOT_FOUND)
            .send()
            .await()
            .body()
        assertThat(r).isEqualTo("There is no run 0")
      }

      ctx.coVerify {
        val r = client.get(port, "localhost", "/processchains/${pc1.id}/runs/3")
            .`as`(BodyCodec.string())
            .expect(ResponsePredicate.SC_NOT_FOUND)
            .send()
            .await()
            .body()
        assertThat(r).isEqualTo("There is no run 3")
      }

      val expectedRun2 = jsonObjectOf(
          "id" to pc1.id,
          "executables" to jsonArrayOf(
              jsonObjectOf(
                  "id" to eid,
                  "path" to "path",
                  "serviceId" to "foobar",
                  "arguments" to jsonArrayOf(),
                  "runtime" to "other",
                  "runtimeArgs" to jsonArrayOf()
              )
          ),
          "requiredCapabilities" to jsonArrayOf(),
          "submissionId" to sid,
          "status" to if (running) ProcessChainStatus.RUNNING.toString() else
            ProcessChainStatus.SUCCESS.toString(),
          "agentId" to agentId2,
          "startTime" to startTime2,
          "totalRuns" to 2,
          "runNumber" to 2
      )
      if (!running) {
        expectedRun2.put("endTime", endTime2)
        expectedRun2.put("results", jsonObjectOf(
            "output_file1" to jsonArrayOf("output.txt")
        ))
      }

      ctx.coVerify {
        val response = client.get(port, "localhost", "/processchains/${pc1.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()

        assertThat(response.body()).isEqualTo(expectedRun2)
      }

      ctx.coVerify {
        val response = client.get(port, "localhost", "/processchains/${pc1.id}/runs/1")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()

        assertThat(response.body()).isEqualTo(jsonObjectOf(
            "id" to pc1.id,
            "executables" to jsonArrayOf(
                jsonObjectOf(
                    "id" to eid,
                    "path" to "path",
                    "serviceId" to "foobar",
                    "arguments" to jsonArrayOf(),
                    "runtime" to "other",
                    "runtimeArgs" to jsonArrayOf()
                )
            ),
            "requiredCapabilities" to jsonArrayOf(),
            "submissionId" to sid,
            "status" to ProcessChainStatus.ERROR.toString(),
            "errorMessage" to error1,
            "agentId" to agentId1,
            "startTime" to startTime1,
            "endTime" to endTime1,
            "totalRuns" to 2,
            "runNumber" to 1,
            "autoResumeAfter" to startTime2
        ))
      }

      ctx.coVerify {
        val response = client.get(port, "localhost", "/processchains/${pc1.id}/runs/2")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()

        assertThat(response.body()).isEqualTo(expectedRun2)
      }

      ctx.completeNow()
    }
  }

  /**
   * Test that the endpoint returns a process chain run
   */
  @Test
  fun getProcessChainRun(vertx: Vertx, ctx: VertxTestContext) {
    doGetProcessChainRun(vertx, ctx)
  }

  /**
   * Test that the endpoint returns a run of a running process chain
   */
  @Test
  fun getProcessChainRunRunning(vertx: Vertx, ctx: VertxTestContext) {
    doGetProcessChainRun(vertx, ctx, true)
  }

  private fun doGetProcessChainRunNoRunWithStatus(vertx: Vertx, ctx: VertxTestContext,
      expectedStatus: ProcessChainStatus, existingRuns: List<Run> = emptyList()) {
    val eid = UniqueID.next()
    val sid = UniqueID.next()
    val pc1 = ProcessChain(executables = listOf(Executable(id = eid,
        path = "path", serviceId = "foobar", arguments = emptyList())))

    coEvery { submissionRegistry.findProcessChainById(pc1.id) } returns pc1
    coEvery { submissionRegistry.getProcessChainSubmissionId(pc1.id) } returns sid
    coEvery { submissionRegistry.getProcessChainStatus(pc1.id) } returns
        expectedStatus
    coEvery { submissionRegistry.countProcessChainRuns(pc1.id) } returns
        existingRuns.size.toLong()
    for ((runIndex, r) in existingRuns.withIndex()) {
      val runNumber = runIndex + 1
      coEvery { submissionRegistry.getProcessChainRun(pc1.id,
          runNumber.toLong()) } returns r
    }
    coEvery { submissionRegistry.getLastProcessChainRun(pc1.id) } returns
        existingRuns.lastOrNull()

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/processchains/${pc1.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()

        val expectedResponse = jsonObjectOf(
            "id" to pc1.id,
            "executables" to jsonArrayOf(
                jsonObjectOf(
                    "id" to eid,
                    "path" to "path",
                    "serviceId" to "foobar",
                    "arguments" to jsonArrayOf(),
                    "runtime" to "other",
                    "runtimeArgs" to jsonArrayOf()
                )
            ),
            "requiredCapabilities" to jsonArrayOf(),
            "submissionId" to sid,
            "status" to expectedStatus.toString(),
            "totalRuns" to existingRuns.size
        )
        if (expectedStatus == ProcessChainStatus.PAUSED &&
            existingRuns.lastOrNull()?.autoResumeAfter != null) {
          expectedResponse.put("autoResumeAfter", existingRuns.last().autoResumeAfter)
        }

        assertThat(response.body()).isEqualTo(expectedResponse)
      }

      ctx.completeNow()
    }
  }

  /**
   * Test that the endpoint does not return a run if the process chain is still
   * REGISTERED and no run has been performed yet
   */
  @Test
  fun getProcessChainRunRegistered(vertx: Vertx, ctx: VertxTestContext) {
    doGetProcessChainRunNoRunWithStatus(vertx, ctx, ProcessChainStatus.REGISTERED)
  }

  /**
   * Test that the endpoint does not return a run if the process chain has been
   * reset to REGISTERED after a run was performed
   */
  @Test
  fun getProcessChainRunRegisteredAfterRun(vertx: Vertx, ctx: VertxTestContext) {
    doGetProcessChainRunNoRunWithStatus(vertx, ctx, ProcessChainStatus.REGISTERED,
        listOf(Run(UniqueID.next(), Instant.now().minusMillis(1000), Instant.now(),
            ProcessChainStatus.SUCCESS)))
  }

  /**
   * Test that the endpoint does not return a run if the process chain has been
   * PAUSED right at the beginning before any run could be performed
   */
  @Test
  fun getProcessChainRunPaused(vertx: Vertx, ctx: VertxTestContext) {
    doGetProcessChainRunNoRunWithStatus(vertx, ctx, ProcessChainStatus.PAUSED)
  }

  /**
   * Test that the endpoint does not return a run if the process chain has been
   * PAUSED after a run was be performed
   */
  @Test
  fun getProcessChainRunPausedAfterRun(vertx: Vertx, ctx: VertxTestContext) {
    doGetProcessChainRunNoRunWithStatus(vertx, ctx, ProcessChainStatus.PAUSED,
        listOf(Run(
            agentId = UniqueID.next(),
            startTime = Instant.now().minusMillis(1000),
            endTime = Instant.now(),
            status = ProcessChainStatus.SUCCESS,
            autoResumeAfter = Instant.now().plusMillis(1000)
        )))
  }

  @Test
  fun onGetProcessChainRuns(vertx: Vertx, ctx: VertxTestContext) {
    val pc1id = UniqueID.next()

    val agentId = UniqueID.next()
    val startTime1 = Instant.now().minusMillis(6000)
    val endTime1 = Instant.now().minusMillis(5000)
    val startTime2 = Instant.now().minusMillis(4000)
    val endTime2 = Instant.now().minusMillis(3000)
    val error1 = "error1"
    val run1 = Run(
        agentId = agentId,
        startTime = startTime1,
        endTime = endTime1,
        status = ProcessChainStatus.ERROR,
        errorMessage = error1,
        autoResumeAfter = startTime2)
    val run2 = Run(
        agentId = agentId,
        startTime = startTime2,
        endTime = endTime2,
        status = ProcessChainStatus.SUCCESS
    )

    coEvery { submissionRegistry.getProcessChainRuns(pc1id) } returns listOf(run1, run2)
    coEvery { submissionRegistry.getProcessChainRuns(not(pc1id)) } throws
        NoSuchElementException()

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val wrongId = "${pc1id}_doesnotexist"
        val r = client.get(port, "localhost", "/processchains/$wrongId/runs")
            .`as`(BodyCodec.string())
            .expect(ResponsePredicate.SC_NOT_FOUND)
            .send()
            .await()
            .body()
        assertThat(r).isEqualTo("There is no process chain with ID `$wrongId'")
      }

      ctx.coVerify {
        val runs = client.get(port, "localhost", "/processchains/${pc1id}/runs")
            .`as`(BodyCodec.jsonArray())
            .expect(ResponsePredicate.SC_OK)
            .send()
            .await()
            .body()
        assertThat(runs).isEqualTo(jsonArrayOf(
            jsonObjectOf(
                "agentId" to agentId,
                "startTime" to startTime1,
                "endTime" to endTime1,
                "status" to "ERROR",
                "errorMessage" to error1,
                "autoResumeAfter" to startTime2
            ),
            jsonObjectOf(
                "agentId" to agentId,
                "startTime" to startTime2,
                "endTime" to endTime2,
                "status" to "SUCCESS"
            )
        ))
      }

      ctx.completeNow()
    }
  }

  /**
   * Test if process chains can be updated
   */
  @Test
  fun putProcessChainById(vertx: Vertx, ctx: VertxTestContext) {
    val sid = UniqueID.next()
    val pc1 = ProcessChain()
    val pc2 = ProcessChain()
    val pc3 = ProcessChain()
    val pc4 = ProcessChain()
    val pc5 = ProcessChain()
    val pc6 = ProcessChain()

    coEvery { submissionRegistry.findProcessChainById(pc1.id) } returns pc1
    coEvery { submissionRegistry.findProcessChainById(pc2.id) } returns pc2
    coEvery { submissionRegistry.findProcessChainById(pc3.id) } returns pc3
    coEvery { submissionRegistry.findProcessChainById(pc4.id) } returns pc4
    coEvery { submissionRegistry.findProcessChainById(pc5.id) } returns pc5
    coEvery { submissionRegistry.findProcessChainById(pc6.id) } returns pc6
    coEvery { submissionRegistry.findProcessChainById("UNKNOWN") } returns null

    coEvery { submissionRegistry.getProcessChainSubmissionId(pc1.id) } returns sid
    coEvery { submissionRegistry.getProcessChainSubmissionId(pc2.id) } returns sid
    coEvery { submissionRegistry.getProcessChainSubmissionId(pc3.id) } returns sid
    coEvery { submissionRegistry.getProcessChainSubmissionId(pc4.id) } returns sid
    coEvery { submissionRegistry.getProcessChainSubmissionId(pc5.id) } returns sid
    coEvery { submissionRegistry.getProcessChainSubmissionId(pc6.id) } returns sid

    val agentId = UniqueID.next()
    val startTime = Instant.now()
    val endTime = Instant.now().plusMillis(120)
    coEvery { submissionRegistry.getLastProcessChainRun(pc1.id) } returns
        Run(agentId, startTime, endTime, ProcessChainStatus.SUCCESS)
    coEvery { submissionRegistry.getLastProcessChainRun(pc2.id) } returns
        Run(agentId, startTime)
    coEvery { submissionRegistry.getLastProcessChainRun(pc3.id) } returns null
    coEvery { submissionRegistry.getLastProcessChainRun(pc4.id) } returns null
    coEvery { submissionRegistry.getLastProcessChainRun(pc5.id) } returns
        Run(agentId, startTime, endTime, ProcessChainStatus.ERROR)
    coEvery { submissionRegistry.getLastProcessChainRun(pc6.id) } returns
        Run(agentId, startTime, endTime, ProcessChainStatus.CANCELLED)

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val cancelledBody = jsonObjectOf(
            "status" to "CANCELLED"
        )

        val expectedPriority = 100
        val priorityBody = jsonObjectOf(
            "priority" to expectedPriority
        )

        val missingBody = jsonObjectOf(
            "foo" to "bar"
        )

        val invalidBody1 = jsonObjectOf(
            "status" to "INVALID"
        )

        val invalidBody2 = jsonObjectOf(
            "status" to 5
        )

        val invalidBody3 = jsonObjectOf(
            "priority" to "INVALID"
        )

        // test invalid requests
        client.put(port, "localhost", "/processchains/${pc1.id}")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .send()
            .await()

        client.put(port, "localhost", "/processchains/${pc1.id}")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .sendBuffer(Buffer.buffer("INVALID BODY"))
            .await()

        client.put(port, "localhost", "/processchains/${pc1.id}")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .sendJsonObject(missingBody)
            .await()

        client.put(port, "localhost", "/processchains/${pc1.id}")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .sendJsonObject(invalidBody1)
            .await()

        client.put(port, "localhost", "/processchains/${pc1.id}")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .sendJsonObject(invalidBody2)
            .await()

        client.put(port, "localhost", "/processchains/${pc1.id}")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .sendJsonObject(invalidBody3)
            .await()

        client.put(port, "localhost", "/processchains/UNKNOWN")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_NOT_FOUND)
            .sendJsonObject(cancelledBody)
            .await()

        client.put(port, "localhost", "/processchains/${pc1.id}")
            .`as`(BodyCodec.none())
            .putHeader("accept", "text/html")
            .expect(ResponsePredicate.SC_NOT_ACCEPTABLE)
            .sendJsonObject(cancelledBody)
            .await()

        // now test valid requests (cancel process chain)
        coEvery { submissionRegistry.getProcessChainStatus(pc1.id) } returns
            ProcessChainStatus.SUCCESS
        val response1 = client.put(port, "localhost", "/processchains/${pc1.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .sendJsonObject(cancelledBody)
            .await()

        assertThat(response1.body()).isEqualTo(jsonObjectOf(
            "id" to pc1.id,
            "requiredCapabilities" to jsonArrayOf(),
            "submissionId" to sid,
            "status" to ProcessChainStatus.SUCCESS.toString(),
            "agentId" to agentId,
            "startTime" to startTime,
            "endTime" to endTime
        ))

        coEvery { submissionRegistry.getProcessChainStatus(pc2.id) } returns
            ProcessChainStatus.RUNNING
        val response2 = client.put(port, "localhost", "/processchains/${pc2.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .sendJsonObject(cancelledBody)
            .await()

        assertThat(response2.body()).isEqualTo(jsonObjectOf(
            "id" to pc2.id,
            "requiredCapabilities" to jsonArrayOf(),
            "submissionId" to sid,
            "status" to ProcessChainStatus.RUNNING.toString(),
            "agentId" to agentId,
            "startTime" to startTime
        ))

        coEvery { submissionRegistry.getProcessChainStatus(pc3.id) } returns
            ProcessChainStatus.REGISTERED andThen ProcessChainStatus.CANCELLED
        coEvery { submissionRegistry.setProcessChainStatus(pc3.id,
            ProcessChainStatus.REGISTERED, ProcessChainStatus.CANCELLED) } just Runs
        val response3 = client.put(port, "localhost", "/processchains/${pc3.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .sendJsonObject(cancelledBody)
            .await()

        assertThat(response3.body()).isEqualTo(jsonObjectOf(
            "id" to pc3.id,
            "requiredCapabilities" to jsonArrayOf(),
            "submissionId" to sid,
            "status" to ProcessChainStatus.CANCELLED.toString()
        ))

        coEvery { submissionRegistry.getProcessChainStatus(pc5.id) } returns
            ProcessChainStatus.PAUSED andThen ProcessChainStatus.CANCELLED
        coEvery { submissionRegistry.setProcessChainStatus(pc5.id,
            ProcessChainStatus.PAUSED, ProcessChainStatus.CANCELLED) } just Runs
        val response5 = client.put(port, "localhost", "/processchains/${pc5.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .sendJsonObject(cancelledBody)
            .await()

        assertThat(response5.body()).isEqualTo(jsonObjectOf(
            "id" to pc5.id,
            "requiredCapabilities" to jsonArrayOf(),
            "submissionId" to sid,
            "status" to ProcessChainStatus.CANCELLED.toString()
        ))

        coVerify(exactly = 1) {
          submissionRegistry.setProcessChainStatus(pc5.id,
              ProcessChainStatus.PAUSED, ProcessChainStatus.CANCELLED)
        }

        coEvery { submissionRegistry.getProcessChainStatus(pc6.id) } returns
            ProcessChainStatus.CANCELLED
        val response6 = client.put(port, "localhost", "/processchains/${pc6.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .sendJsonObject(cancelledBody)
            .await()

        assertThat(response6.body()).isEqualTo(jsonObjectOf(
            "id" to pc6.id,
            "requiredCapabilities" to jsonArrayOf(),
            "submissionId" to sid,
            "status" to ProcessChainStatus.CANCELLED.toString(),
            "agentId" to agentId,
            "startTime" to startTime,
            "endTime" to endTime
        ))

        coVerify(exactly = 0) {
          submissionRegistry.setProcessChainStatus(pc6.id, any(),
              ProcessChainStatus.CANCELLED)
        }

        // set process chain priority
        coEvery { submissionRegistry.getProcessChainStatus(pc4.id) } returns
            ProcessChainStatus.REGISTERED
        coEvery { submissionRegistry.setProcessChainPriority(pc4.id, any()) } returns true
        coEvery { submissionRegistry.findProcessChainById(pc4.id) } returns pc4.copy(priority = expectedPriority)
        val response4 = client.put(port, "localhost", "/processchains/${pc4.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .sendJsonObject(priorityBody)
            .await()

        assertThat(response4.body()).isEqualTo(jsonObjectOf(
            "id" to pc4.id,
            "requiredCapabilities" to jsonArrayOf(),
            "submissionId" to sid,
            "status" to ProcessChainStatus.REGISTERED.toString(),
            "priority" to expectedPriority
        ))
        coVerify(exactly = 1) {
          submissionRegistry.setProcessChainPriority(pc4.id, expectedPriority)
        }

        // try to change priority of finished process chain
        coEvery { submissionRegistry.getProcessChainStatus(pc4.id) } returns
            ProcessChainStatus.SUCCESS
        client.put(port, "localhost", "/processchains/${pc4.id}")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_UNPROCESSABLE_ENTITY)
            .sendJsonObject(priorityBody)
            .await()
      }

      ctx.completeNow()
    }
  }

  /**
   * Test that the endpoint returns a list of VMs
   */
  @Test
  fun getVMs(vertx: Vertx, ctx: VertxTestContext) {
    val vm1 = VM(setup = setup, status = VM.Status.CREATING)
    val vm2 = VM(setup = setup, status = VM.Status.PROVISIONING)
    val vm3 = VM(setup = setup, status = VM.Status.RUNNING)

    coEvery { vmRegistry.findVMs(size = 10, order = -1) } returns listOf(vm1, vm2, vm3)
    coEvery { vmRegistry.countVMs() } returns 3

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/vms")
            .`as`(BodyCodec.jsonArray())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()

        assertThat(response.headers()["x-page-size"]).isEqualTo("10")
        assertThat(response.headers()["x-page-offset"]).isEqualTo("0")
        assertThat(response.headers()["x-page-total"]).isEqualTo("3")

        assertThat(response.body()).isEqualTo(jsonArrayOf(
            jsonObjectOf(
                "id" to vm1.id,
                "setup" to JsonUtils.toJson(setup),
                "status" to VM.Status.CREATING.toString()
            ),
            jsonObjectOf(
                "id" to vm2.id,
                "setup" to JsonUtils.toJson(setup),
                "status" to VM.Status.PROVISIONING.toString()
            ),
            jsonObjectOf(
                "id" to vm3.id,
                "setup" to JsonUtils.toJson(setup),
                "status" to VM.Status.RUNNING.toString()
            )
        ))
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that the endpoint returns a list of VMs with a given status
   */
  @Test
  fun getVMsByStatus(vertx: Vertx, ctx: VertxTestContext) {
    val vm1 = VM(setup = setup, status = VM.Status.CREATING)
    val vm2 = VM(setup = setup, status = VM.Status.CREATING)

    coEvery { vmRegistry.findVMs(status = VM.Status.CREATING, size = 10,
        order = -1) } returns listOf(vm1, vm2)
    coEvery { vmRegistry.countVMs(status = VM.Status.CREATING) } returns 2

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/vms?status=CREATING")
            .`as`(BodyCodec.jsonArray())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()

        assertThat(response.headers()["x-page-size"]).isEqualTo("10")
        assertThat(response.headers()["x-page-offset"]).isEqualTo("0")
        assertThat(response.headers()["x-page-total"]).isEqualTo("2")

        assertThat(response.body()).isEqualTo(jsonArrayOf(
            jsonObjectOf(
                "id" to vm1.id,
                "setup" to JsonUtils.toJson(setup),
                "status" to VM.Status.CREATING.toString()
            ),
            jsonObjectOf(
                "id" to vm2.id,
                "setup" to JsonUtils.toJson(setup),
                "status" to VM.Status.CREATING.toString()
            )
        ))
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if the process chain logs endpoint returns 404 if the process chain
   * does not exist in the registry
   */
  @Test
  fun getProcessChainLogByIdUnknownProcessChain(vertx: Vertx, ctx: VertxTestContext) {
    val id = "abcdef123456"

    coEvery { submissionRegistry.getProcessChainStatus(id) } throws NoSuchElementException()

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        client.get(port, "localhost", "/logs/processchains/$id")
            .expect(ResponsePredicate.SC_NOT_FOUND)
            .send()
            .await()
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if the process chain logs endpoint returns 404 if there is no agent
   */
  @Test
  fun getProcessChainLogByIdNoAgent(vertx: Vertx, ctx: VertxTestContext) {
    val id = "abcdef123456"

    coEvery { submissionRegistry.getProcessChainStatus(id) } returns
        ProcessChainStatus.REGISTERED
    coEvery { submissionRegistry.countProcessChainRuns(id) } returns 0

    coEvery { agentRegistry.getPrimaryAgentIds() } returns emptySet()

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        client.get(port, "localhost", "/logs/processchains/$id")
            .expect(ResponsePredicate.SC_NOT_FOUND)
            .send()
            .await()
      }
      ctx.completeNow()
    }
  }

  private fun prepareGetProcessChainLogById(vertx: Vertx, ctx: VertxTestContext,
      id: String, contents: String, start: Int = 0, end: Int = contents.length,
      agent1Asked: AtomicInteger, agent2Asked: AtomicInteger,
      checkOnly: Boolean = false, errorMessage: String? = null) {
    prepareGetProcessChainLogById(vertx, ctx, id, listOf(contents),
        listOf(start to end), agent1Asked, agent2Asked, checkOnly, errorMessage)
  }

  private fun prepareGetProcessChainLogById(vertx: Vertx, ctx: VertxTestContext,
      id: String, contents: List<String>, startEnd: List<Pair<Int, Int>>,
      agent1Asked: AtomicInteger, agent2Asked: AtomicInteger,
      checkOnly: Boolean = false, errorMessage: String? = null) {
    val agentId1 = "agent1"
    val agentId2 = "agent2"

    coEvery { submissionRegistry.getProcessChainStatus(id) } returns
        ProcessChainStatus.SUCCESS
    coEvery { submissionRegistry.countProcessChainRuns(id) } returns
        contents.size.toLong()

    coEvery { agentRegistry.getPrimaryAgentIds() } returns setOf(agentId1, agentId2)

    // create an agent that does not know the log file
    val address1 = REMOTE_AGENT_ADDRESS_PREFIX + agentId1 +
        REMOTE_AGENT_PROCESSCHAINLOGS_SUFFIX
    vertx.eventBus().consumer<JsonObject>(address1) { msg ->
      agent1Asked.getAndIncrement()
      val obj = msg.body()
      ctx.verify {
        assertThat(obj.getString("id")).isEqualTo(id)
        val replyAddress = obj.getString("replyAddress")
        val runNumber = obj.getLong("runNumber").toInt()
        val start = startEnd[runNumber - 1].first
        val end = startEnd[runNumber - 1].second
        assertThat(replyAddress).matches(Pattern.quote("$address1.reply.") + ".+")
        if (end < start) {
          vertx.eventBus().send(replyAddress, jsonObjectOf(
              "error" to 416
          ))
        } else {
          vertx.eventBus().send(replyAddress, jsonObjectOf(
              "error" to 404
          ))
        }
      }
    }

    // create an agent that returns the log file
    val address2 = REMOTE_AGENT_ADDRESS_PREFIX + agentId2 +
        REMOTE_AGENT_PROCESSCHAINLOGS_SUFFIX
    vertx.eventBus().consumer<JsonObject>(address2) { msg ->
      agent2Asked.getAndIncrement()
      val obj = msg.body()
      val runNumber = obj.getLong("runNumber").toInt()
      val start = startEnd[runNumber - 1].first
      val end = startEnd[runNumber - 1].second
      val cts = contents[runNumber - 1]
      CoroutineScope(vertx.dispatcher()).launch {
        ctx.coVerify {
          if (start != 0) {
            assertThat(obj.getLong("start")).isEqualTo(start.toLong())
          }
          if (end != cts.length) {
            assertThat(obj.getLong("end")).isEqualTo(end.toLong() - 1L)
          }
          assertThat(obj.getString("id")).isEqualTo(id)
          val replyAddress = obj.getString("replyAddress")
          assertThat(replyAddress).matches(Pattern.quote("$address2.reply.") + ".+")

          if (errorMessage != null) {
            vertx.eventBus().send(replyAddress, jsonObjectOf(
                "error" to 500,
                "message" to errorMessage
            ))
          } else {
            vertx.eventBus().request<Unit>(replyAddress, jsonObjectOf(
                "size" to cts.length.toLong(),
                "start" to start.toLong(),
                "end" to end.toLong() - 1L,
                "length" to (end - start).toLong()
            )).await()

            if (!checkOnly) {
              val chunk = jsonObjectOf(
                  "data" to cts.substring(start, end)
              )
              vertx.eventBus().request<Unit>(replyAddress, chunk).await()
              vertx.eventBus().send(replyAddress, JsonObject())
            }
          }
        }
      }
    }
  }

  /**
   * Test if we can get the contents of a process chain log file
   */
  @Test
  fun getProcessChainLogById(vertx: Vertx, ctx: VertxTestContext) {
    val id = "abcdef123456"
    val contents = "Hello world"

    val agent1Asked = AtomicInteger(0)
    val agent2Asked = AtomicInteger(0)
    prepareGetProcessChainLogById(vertx, ctx, id, contents,
        agent1Asked = agent1Asked, agent2Asked = agent2Asked)

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/logs/processchains/$id")
            .`as`(BodyCodec.string())
            .expect(ResponsePredicate.SC_OK)
            .expect(contentType("text/plain"))
            .send()
            .await()

        assertThat(agent1Asked.get()).isEqualTo(1)
        assertThat(agent2Asked.get()).isEqualTo(1)

        assertThat(response.body()).isEqualTo(contents)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if we can get a subset of the contents of a process chain log file
   */
  @Test
  fun getProcessChainLogByIdRange(vertx: Vertx, ctx: VertxTestContext) {
    val id = "abcdef123456"
    val contents = "Hello world"

    val agent1Asked = AtomicInteger(0)
    val agent2Asked = AtomicInteger(0)
    prepareGetProcessChainLogById(vertx, ctx, id, contents, start = 2, end = 3,
        agent1Asked = agent1Asked, agent2Asked = agent2Asked)

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/logs/processchains/$id")
            .`as`(BodyCodec.string())
            .putHeader("Range", "bytes=2-2")
            .expect(ResponsePredicate.SC_PARTIAL_CONTENT)
            .expect(contentType("text/plain"))
            .send()
            .await()

        assertThat(agent1Asked.get()).isEqualTo(1)
        assertThat(agent2Asked.get()).isEqualTo(1)

        assertThat(response.getHeader("Content-Length")).isEqualTo("1")
        assertThat(response.getHeader("Content-Range")).isEqualTo("bytes 2-2/11")
        assertThat(response.getHeader("Accept-Ranges")).isEqualTo("bytes")
        assertThat(response.body()).isEqualTo(contents.substring(2, 3))
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if we can get a subset of the contents of a process chain log file
   * using our own `X-Range` HTTP header
   */
  @Test
  fun getProcessChainLogByIdXRange(vertx: Vertx, ctx: VertxTestContext) {
    val id = "abcdef123456"
    val contents = "Hello world"

    val agent1Asked = AtomicInteger(0)
    val agent2Asked = AtomicInteger(0)
    prepareGetProcessChainLogById(vertx, ctx, id, contents, start = 2, end = 3,
        agent1Asked = agent1Asked, agent2Asked = agent2Asked)

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/logs/processchains/$id")
            .`as`(BodyCodec.string())
            .putHeader("X-Range", "bytes=2-2")
            .expect(ResponsePredicate.SC_OK)
            .expect(contentType("text/plain"))
            .send()
            .await()

        assertThat(agent1Asked.get()).isEqualTo(1)
        assertThat(agent2Asked.get()).isEqualTo(1)

        assertThat(response.getHeader("Content-Length")).isEqualTo("1")
        assertThat(response.getHeader("X-Content-Range")).isEqualTo("bytes 2-2/11")
        assertThat(response.getHeader("Accept-Ranges")).isEqualTo("bytes")
        assertThat(response.body()).isEqualTo(contents.substring(2, 3))
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if we can get the size of a process chain log file
   */
  @Test
  fun getProcessChainLogByIdHeadersOnly(vertx: Vertx, ctx: VertxTestContext) {
    val id = "abcdef123456789"
    val contents = "Hello world!"

    val agent1Asked = AtomicInteger(0)
    val agent2Asked = AtomicInteger(0)
    prepareGetProcessChainLogById(vertx, ctx, id, contents,
        agent1Asked = agent1Asked, agent2Asked = agent2Asked, checkOnly = true)

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val response = client.head(port, "localhost", "/logs/processchains/$id")
            .`as`(BodyCodec.string())
            .expect(ResponsePredicate.SC_OK)
            .send()
            .await()

        assertThat(response.getHeader("Content-Length").toInt())
            .isEqualTo(contents.length)

        assertThat(agent1Asked.get()).isEqualTo(1)
        assertThat(agent2Asked.get()).isEqualTo(1)

        assertThat(response.body()).isNull()
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if getProcessChainLogById() can handle errors
   */
  @Test
  fun getProcessChainLogByIdError(vertx: Vertx, ctx: VertxTestContext) {
    val id = "abcdef123456789"
    val contents = "Hello world!"
    val error = "THIS IS AN ERROR!"

    val agent1Asked = AtomicInteger(0)
    val agent2Asked = AtomicInteger(0)
    prepareGetProcessChainLogById(vertx, ctx, id, contents,
        agent1Asked = agent1Asked, agent2Asked = agent2Asked,
        errorMessage = error)

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val response = client.head(port, "localhost", "/logs/processchains/$id")
            .`as`(BodyCodec.string())
            .expect(ResponsePredicate.SC_SERVER_ERRORS)
            .send()
            .await()

        assertThat(agent1Asked.get()).isEqualTo(1)
        assertThat(agent2Asked.get()).isEqualTo(1)

        assertThat(response.body()).isNull()
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if we can handle invalid range requests
   */
  @Test
  fun getProcessChainLogByIdRangeInvalid(vertx: Vertx, ctx: VertxTestContext) {
    val id = "abcdef123456"
    val contents = "Hello world"

    val agent1Asked = AtomicInteger(0)
    val agent2Asked = AtomicInteger(0)
    prepareGetProcessChainLogById(vertx, ctx, id, contents, start = 3, end = 2,
        agent1Asked = agent1Asked, agent2Asked = agent2Asked)

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        client.get(port, "localhost", "/logs/processchains/$id")
            .`as`(BodyCodec.string())
            .putHeader("Range", "bytes=3-2")
            .expect(ResponsePredicate.SC_REQUESTED_RANGE_NOT_SATISFIABLE)
            .send()
            .await()

        assertThat(agent1Asked.get()).isEqualTo(1)
        assertThat(agent2Asked.get()).isEqualTo(0)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if we can handle incorrect run numbers
   */
  @Test
  fun getProcessChainLogByIdIncorrectRunNumber(vertx: Vertx, ctx: VertxTestContext) {
    val id = "abcdef123456"
    val client = WebClient.create(vertx)

    coEvery { submissionRegistry.getProcessChainStatus(id) } returns
        ProcessChainStatus.SUCCESS
    coEvery { submissionRegistry.countProcessChainRuns(id) } returns 1

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val body = client.get(port, "localhost", "/logs/processchains/$id?runNumber=foobar")
            .`as`(BodyCodec.string())
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .send()
            .await()
            .body()
        assertThat(body).isEqualTo("Invalid run number")
      }

      ctx.coVerify {
        val body = client.get(port, "localhost", "/logs/processchains/$id?runNumber=2")
            .`as`(BodyCodec.string())
            .expect(ResponsePredicate.SC_NOT_FOUND)
            .send()
            .await()
            .body()
        assertThat(body).contains("Run number out of range")
      }

      ctx.coVerify {
        val body = client.get(port, "localhost", "/logs/processchains/$id?runNumber=0")
            .`as`(BodyCodec.string())
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .send()
            .await()
            .body()
        assertThat(body).contains("Run number must be greater than 0")
      }

      ctx.completeNow()
    }
  }

  /**
   * Test if the endpoint does not return a log file if the process chain
   * is currently REGISTERED or PAUSED
   */
  @Test
  fun getProcessChainLogByIdNotStarted(vertx: Vertx, ctx: VertxTestContext) {
    val id = "abcdef123456"
    val client = WebClient.create(vertx)

    CoroutineScope(vertx.dispatcher()).launch {
      for (s in listOf(ProcessChainStatus.REGISTERED, ProcessChainStatus.PAUSED)) {
        ctx.coVerify {
          coEvery { submissionRegistry.getProcessChainStatus(id) } returns s
          val body = client.get(port, "localhost", "/logs/processchains/$id")
              .`as`(BodyCodec.string())
              .expect(ResponsePredicate.SC_NOT_FOUND)
              .send()
              .await()
              .body()
          assertThat(body).contains("$s and does not have a log file")
        }
      }

      ctx.completeNow()
    }
  }

  /**
   * Test if we can get the contents of process chain log files for different
   * run numbers
   */
  @Test
  fun getProcessChainLogByIdRunNumber(vertx: Vertx, ctx: VertxTestContext) {
    val id = "abcdef123456"
    val contents1 = "Hello world 1"
    val contents2 = "Hello world 2"

    val agent1Asked = AtomicInteger(0)
    val agent2Asked = AtomicInteger(0)
    prepareGetProcessChainLogById(vertx, ctx, id, listOf(contents1, contents2),
        listOf(0 to contents1.length, 0 to contents2.length),
        agent1Asked = agent1Asked, agent2Asked = agent2Asked)

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      val tests = listOf(
          // check that we can get the latest log file and previous log files
          // from a process chain that has succeeded
          ProcessChainStatus.SUCCESS to listOf(
              Triple(null, contents2, ResponsePredicate.SC_OK),
              Triple(1, contents1, ResponsePredicate.SC_OK),
              Triple(2, contents2, ResponsePredicate.SC_OK)
          ),
          // check that we can still get log files from previous runs even if
          // the process chain is currently paused
          ProcessChainStatus.PAUSED to listOf(
              Triple(
                  null,
                  "Process chain `$id' is PAUSED and does not have a log file",
                  ResponsePredicate.SC_NOT_FOUND
              ),
              Triple(1, contents1, ResponsePredicate.SC_OK),
              Triple(2, contents2, ResponsePredicate.SC_OK)
          ),
      )

      var i = -1
      for (t in tests) {
        coEvery { submissionRegistry.getProcessChainStatus(id) } returns t.first
        for (p in t.second) {
          if (p.third == ResponsePredicate.SC_OK) {
            ++i
          }
          ctx.coVerify {
            val param = when (p.first) {
              null -> ""
              else -> "?runNumber=${p.first}"
            }
            val response = client.get(port, "localhost", "/logs/processchains/$id$param")
                .`as`(BodyCodec.string())
                .expect(p.third)
                .expect(contentType("text/plain"))
                .send()
                .await()

            assertThat(agent1Asked.get()).isEqualTo(1 + i)
            assertThat(agent2Asked.get()).isEqualTo(1 + i)

            assertThat(response.body()).isEqualTo(p.second)
          }
        }
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if we can get information about agents
   */
  @Test
  fun getAgents(vertx: Vertx, ctx: VertxTestContext) {
    val agentId1 = UniqueID.next()
    val agentId2 = UniqueID.next()
    val agents = setOf(agentId1, agentId2)
    coEvery { agentRegistry.getAgentIds() } returns agents

    for (a in agents) {
      val address1 = REMOTE_AGENT_ADDRESS_PREFIX + a
      vertx.eventBus().consumer<JsonObject>(address1) { msg ->
        ctx.verify {
          assertThat(msg.body().getString("action")).isEqualTo("info")
        }
        msg.reply(jsonObjectOf("id" to a))
      }
    }

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val client = WebClient.create(vertx)
        val response = client.get(port, "localhost", "/agents")
            .`as`(BodyCodec.jsonArray())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()
        assertThat(response.body()).isEqualTo(jsonArrayOf(
          jsonObjectOf("id" to agentId1), jsonObjectOf("id" to agentId2)))
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if we get a 503 response if an agent is not available
   */
  @Test
  fun getAgentsNotAvailable(vertx: Vertx, ctx: VertxTestContext) {
    val agentId1 = UniqueID.next()
    val agentId2 = UniqueID.next()
    val agents = setOf(agentId1, agentId2)
    coEvery { agentRegistry.getAgentIds() } returns agents

    val address1 = REMOTE_AGENT_ADDRESS_PREFIX + agentId1
    vertx.eventBus().consumer<JsonObject>(address1) { msg ->
      ctx.verify {
        assertThat(msg.body().getString("action")).isEqualTo("info")
      }
      msg.reply(jsonObjectOf("id" to agentId1))
    }

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val client = WebClient.create(vertx)
        client.get(port, "localhost", "/agents")
            .expect(ResponsePredicate.SC_SERVICE_UNAVAILABLE)
            .send()
            .await()
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if we can get information about health
   */
  private fun getHealthWithEnabledRegistries(vertx: Vertx, ctx: VertxTestContext,
      services: Boolean = true, agents: Boolean = true, submissions: Boolean = true, vms: Boolean = true) {

    // Mock the registries and let them throw an error if they should not be available
    val errorMessage = "Unhealthy"
    coEvery { metadataRegistry.findServices() }.apply {
      if (services) returns(listOf()) else throws(RuntimeException(errorMessage))
    }
    coEvery { agentRegistry.getAgentIds() }.apply {
      if (agents) returns(setOf()) else throws(RuntimeException(errorMessage))
    }
    coEvery { submissionRegistry.countSubmissions() }.apply {
      if (submissions) returns(0) else throws(RuntimeException(errorMessage))
    }
    coEvery { vmRegistry.countVMs() }.apply {
      if (vms) returns(0) else throws(RuntimeException(errorMessage))
    }

    // The expected output for a registry in the response json
    val expectedOutput = { healthy: Boolean ->
      jsonObjectOf(
        "health" to healthy,
        "count" to if (healthy) 0 else -1
      ).apply { if (!healthy) put("error", errorMessage) }
    }

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val systemHealthy = services && agents && submissions && vms
        val client = WebClient.create(vertx)
        val response = client.get(port, "localhost", "/health")
            .`as`(BodyCodec.jsonObject())
            .expect(if (systemHealthy) ResponsePredicate.SC_OK else ResponsePredicate.SC_SERVICE_UNAVAILABLE)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()
        assertThat(response.body()).isEqualTo(jsonObjectOf(
            "services" to expectedOutput(services),
            "agents" to expectedOutput(agents),
            "submissions" to expectedOutput(submissions),
            "vms" to expectedOutput(vms),
            "health" to systemHealthy
        ))
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if we can get information about health if all registries are working correctly
   */
  @Test
  fun getHealth(vertx: Vertx, ctx: VertxTestContext) = getHealthWithEnabledRegistries(vertx, ctx)

  /**
   * Test if we can get information about health if services can not be fetched
   */
  @Test
  fun getHealthFailingServices(vertx: Vertx, ctx: VertxTestContext) =
    getHealthWithEnabledRegistries(vertx, ctx, services = false)

  /**
   * Test if we can get information about health if agents can not be fetched
   */
  @Test
  fun getHealthFailingAgents(vertx: Vertx, ctx: VertxTestContext) =
    getHealthWithEnabledRegistries(vertx, ctx, agents = false)

  /**
   * Test if we can get information about health if submissions can not be fetched
   */
  @Test
  fun getHealthFailingSubmissions(vertx: Vertx, ctx: VertxTestContext) =
    getHealthWithEnabledRegistries(vertx, ctx, submissions = false)

  /**
   * Test if we can get information about health if vms can not be fetched
   */
  @Test
  fun getHealthFailingVms(vertx: Vertx, ctx: VertxTestContext) =
    getHealthWithEnabledRegistries(vertx, ctx, vms = false)

  /**
   * Test that the search endpoint returns a list of results
   */
  @Test
  fun getSearch(vertx: Vertx, ctx: VertxTestContext) {
    val id1 = UniqueID.next()
    val id2 = UniqueID.next()
    val queryStr = "foo bar"
    val encodedQuery = URLEncoder.encode(queryStr, StandardCharsets.UTF_8)
    val query = QueryCompiler.compile(queryStr)

    val serverZoneId = ZoneId.systemDefault()
    val startTime = LocalDateTime.of(2022, 5, 31, 7, 2).atZone(serverZoneId).toInstant()
    val endTime = LocalDateTime.of(2022, 5, 31, 8, 10).atZone(serverZoneId).toInstant()
    val results = listOf(
        SearchResult(
            id = id1,
            type = Type.WORKFLOW,
            name = "Elvis",
            requiredCapabilities = setOf("foo", "bar"),
            status = Submission.Status.SUCCESS.name,
            startTime = startTime,
            endTime = endTime
        ),
        SearchResult(
            id = id2,
            type = Type.PROCESS_CHAIN,
            status = ProcessChainStatus.ERROR.name
        )
    )

    coEvery { submissionRegistry.search(QueryCompiler.compile(""),
        any(), any(), any()) } returns emptyList()
    coEvery { submissionRegistry.search(query,
        size = 10, offset = 0, order = any()) } returns results
    coEvery { submissionRegistry.search(query,
        size = 0, offset = 0, order = any()) } returns emptyList()

    coEvery { submissionRegistry.searchCount(QueryCompiler.compile(""),
        any(), any()) } returns 0L
    coEvery { submissionRegistry.searchCount(query, Type.WORKFLOW,
        false) } returns 1L
    coEvery { submissionRegistry.searchCount(query, Type.PROCESS_CHAIN,
        false) } returns 1L
    coEvery { submissionRegistry.searchCount(query, Type.WORKFLOW,
        true) } returns 2L
    coEvery { submissionRegistry.searchCount(query, Type.PROCESS_CHAIN,
        true) } returns 3L

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        // return no results
        val response = client.get(port, "localhost", "/search")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()

        assertThat(response.headers()["x-page-size"]).isEqualTo("10")
        assertThat(response.headers()["x-page-offset"]).isEqualTo("0")
        assertThat(response.headers()["x-page-total"]).isEqualTo("0")
        assertThat(response.body()).isEqualTo(jsonObjectOf(
            "counts" to jsonObjectOf(
                "workflow" to 0L,
                "processChain" to 0L,
                "total" to 0L
            ),
            "results" to jsonArrayOf()
        ))
      }

      val expectedResults = jsonArrayOf(
          jsonObjectOf(
              "id" to id1,
              "type" to "workflow",
              "name" to "Elvis",
              "requiredCapabilities" to jsonArrayOf("foo", "bar"),
              "status" to "SUCCESS",
              "startTime" to ISO_INSTANT.format(startTime),
              "endTime" to ISO_INSTANT.format(endTime),
              "matches" to jsonArrayOf(
                  jsonObjectOf(
                      "locator" to "requiredCapabilities",
                      "fragment" to "foo",
                      "termMatches" to jsonArrayOf(
                          jsonObjectOf(
                              "term" to "foo",
                              "indices" to jsonArrayOf(0)
                          )
                      )
                  ),
                  jsonObjectOf(
                      "locator" to "requiredCapabilities",
                      "fragment" to "bar",
                      "termMatches" to jsonArrayOf(
                          jsonObjectOf(
                              "term" to "bar",
                              "indices" to jsonArrayOf(0)
                          )
                      )
                  )
              )
          ),
          jsonObjectOf(
              "id" to id2,
              "type" to "processChain",
              "requiredCapabilities" to jsonArrayOf(),
              "status" to "ERROR",
              "matches" to jsonArrayOf()
          )
      )

      ctx.coVerify {
        val response = client.get(port, "localhost", "/search?q=$encodedQuery")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()

        assertThat(response.headers()["x-page-size"]).isEqualTo("10")
        assertThat(response.headers()["x-page-offset"]).isEqualTo("0")
        assertThat(response.headers()["x-page-total"]).isEqualTo("2")
        assertThat(response.body()).isEqualTo(jsonObjectOf(
            "counts" to jsonObjectOf(
                "workflow" to 1L,
                "processChain" to 1L,
                "total" to 2L
            ),
            "results" to expectedResults
        ))
      }

      ctx.coVerify {
        client.get(port, "localhost", "/search?q=$encodedQuery&count=invalid")
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .send()
            .await()
      }

      ctx.coVerify {
        val response = client.get(port, "localhost",
              "/search?q=$encodedQuery&count=none")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()

        assertThat(response.headers()["x-page-size"]).isEqualTo("10")
        assertThat(response.headers()["x-page-offset"]).isEqualTo("0")
        assertThat(response.headers()["x-page-total"]).isNull()
        assertThat(response.body()).isEqualTo(jsonObjectOf(
            "results" to expectedResults
        ))
      }

      ctx.coVerify {
        val response = client.get(port, "localhost",
              "/search?q=$encodedQuery&count=none&size=0")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()

        assertThat(response.headers()["x-page-size"]).isEqualTo("0")
        assertThat(response.headers()["x-page-offset"]).isEqualTo("0")
        assertThat(response.headers()["x-page-total"]).isNull()
        assertThat(response.body()).isEqualTo(jsonObjectOf(
            "results" to jsonArrayOf()
        ))
      }

      ctx.coVerify {
        val response = client.get(port, "localhost",
              "/search?q=$encodedQuery&count=estimate&size=0")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()

        assertThat(response.headers()["x-page-size"]).isEqualTo("0")
        assertThat(response.headers()["x-page-offset"]).isEqualTo("0")
        assertThat(response.headers()["x-page-total"]).isEqualTo("5")
        assertThat(response.body()).isEqualTo(jsonObjectOf(
            "counts" to jsonObjectOf(
                "workflow" to 2L,
                "processChain" to 3L,
                "total" to 5L
            ),
            "results" to jsonArrayOf()
        ))
      }

      ctx.coVerify {
        val response = client.get(port, "localhost",
              "/search?q=$encodedQuery&count=exact&size=0")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()

        assertThat(response.headers()["x-page-size"]).isEqualTo("0")
        assertThat(response.headers()["x-page-offset"]).isEqualTo("0")
        assertThat(response.headers()["x-page-total"]).isEqualTo("2")
        assertThat(response.body()).isEqualTo(jsonObjectOf(
            "counts" to jsonObjectOf(
                "workflow" to 1L,
                "processChain" to 1L,
                "total" to 2L
            ),
            "results" to jsonArrayOf()
        ))
      }

      ctx.completeNow()
    }
  }

  /**
   * Test that the search endpoint returns a list of results according to a
   * given time zone
   */
  @Test
  fun getSearchTimeZone(vertx: Vertx, ctx: VertxTestContext) {
    val id1 = UniqueID.next()

    val serverZoneId = ZoneId.of("Europe/Berlin")
    val startTime = LocalDateTime.of(2022, 5, 31, 7, 2).atZone(serverZoneId).toInstant()
    val endTime = LocalDateTime.of(2022, 5, 31, 8, 10).atZone(serverZoneId).toInstant()
    val results = listOf(
        SearchResult(
            id = id1,
            type = Type.WORKFLOW,
            status = Submission.Status.SUCCESS.name,
            startTime = startTime,
            endTime = endTime
        )
    )

    val query = "2022-05-31T07:02"
    coEvery { submissionRegistry.search(QueryCompiler.compile(query,
        serverZoneId), any(), any(), any()) } returns results
    coEvery { submissionRegistry.searchCount(QueryCompiler.compile(query,
        serverZoneId), Type.WORKFLOW, any()) } returns 1L
    coEvery { submissionRegistry.searchCount(QueryCompiler.compile(query,
        serverZoneId), Type.PROCESS_CHAIN, any()) } returns 0L
    coEvery { submissionRegistry.search(QueryCompiler.compile(query,
        ZoneId.of("America/Vancouver")), any(), any(), any()) } returns emptyList()
    coEvery { submissionRegistry.searchCount(QueryCompiler.compile(query,
        ZoneId.of("America/Vancouver")), any(), any()) } returns 0L

    val client = WebClient.create(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      val expectedResults = jsonArrayOf(
          jsonObjectOf(
              "id" to id1,
              "type" to "workflow",
              "status" to "SUCCESS",
              "requiredCapabilities" to jsonArrayOf(),
              "startTime" to ISO_INSTANT.format(startTime),
              "endTime" to ISO_INSTANT.format(endTime),
              "matches" to jsonArrayOf(
                  jsonObjectOf(
                      "locator" to "startTime",
                      "fragment" to ISO_INSTANT.format(startTime),
                      "termMatches" to jsonArrayOf(
                          jsonObjectOf(
                              "term" to query
                          )
                      )
                  )
              )
          )
      )

      ctx.coVerify {
        val response = client.get(port, "localhost",
            "/search?q=$query&timeZone=Europe/Berlin")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()

        assertThat(response.headers()["x-page-size"]).isEqualTo("10")
        assertThat(response.headers()["x-page-offset"]).isEqualTo("0")
        assertThat(response.headers()["x-page-total"]).isEqualTo("1")
        assertThat(response.body()).isEqualTo(jsonObjectOf(
            "counts" to jsonObjectOf(
                "workflow" to 1L,
                "processChain" to 0L,
                "total" to 1L
            ),
            "results" to expectedResults
        ))
      }

      ctx.coVerify {
        val response = client.get(port, "localhost",
            "/search?q=$query&timeZone=America/Vancouver")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .send()
            .await()

        assertThat(response.headers()["x-page-size"]).isEqualTo("10")
        assertThat(response.headers()["x-page-offset"]).isEqualTo("0")
        assertThat(response.headers()["x-page-total"]).isEqualTo("0")
        assertThat(response.body()).isEqualTo(jsonObjectOf(
            "counts" to jsonObjectOf(
                "workflow" to 0L,
                "processChain" to 0L,
                "total" to 0L
            ),
            "results" to jsonArrayOf()
        ))
      }

      ctx.coVerify {
        client.get(port, "localhost", "/search?q=$query&timeZone=invalid")
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .send()
            .await()
      }

      ctx.completeNow()
    }
  }
}
