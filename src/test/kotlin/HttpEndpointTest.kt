import AddressConstants.LOCAL_AGENT_ADDRESS_PREFIX
import AddressConstants.REMOTE_AGENT_ADDRESS_PREFIX
import AddressConstants.REMOTE_AGENT_PROCESSCHAINLOGS_SUFFIX
import agent.AgentRegistry
import agent.AgentRegistryFactory
import com.fasterxml.jackson.module.kotlin.convertValue
import db.MetadataRegistry
import db.MetadataRegistryFactory
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
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.predicate.ResponsePredicate
import io.vertx.ext.web.client.predicate.ResponsePredicate.contentType
import io.vertx.ext.web.codec.BodyCodec
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.deploymentOptionsOf
import io.vertx.kotlin.core.eventbus.requestAwait
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.jsonArrayOf
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.kotlin.ext.web.client.sendAwait
import io.vertx.kotlin.ext.web.client.sendBufferAwait
import io.vertx.kotlin.ext.web.client.sendJsonObjectAwait
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import model.Submission
import model.cloud.VM
import model.metadata.Cardinality
import model.metadata.Service
import model.metadata.ServiceParameter
import model.processchain.Argument
import model.processchain.Executable
import model.processchain.ProcessChain
import model.setup.Setup
import model.workflow.ExecuteAction
import model.workflow.ForEachAction
import model.workflow.GenericParameter
import model.workflow.OutputParameter
import model.workflow.Variable
import model.workflow.Workflow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * Tests for [HttpEndpoint]
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class HttpEndpointTest {
  private val maxPostSize = 1024
  private var port: Int = 0
  private lateinit var agentRegistry: AgentRegistry
  private lateinit var submissionRegistry: SubmissionRegistry
  private lateinit var metadataRegistry: MetadataRegistry
  private lateinit var vmRegistry: VMRegistry

  private val setup = Setup(id = "test-setup", flavor = "myflavor",
      imageName = "myimage", availabilityZone = "my-az", blockDeviceSizeGb = 20,
      maxVMs = 10)

  @BeforeEach
  fun setUp(vertx: Vertx, ctx: VertxTestContext) {
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

    // mock VM registry
    vmRegistry = mockk()
    mockkObject(VMRegistryFactory)
    every { VMRegistryFactory.create(any()) } returns vmRegistry

    // deploy verticle under test
    val config = json {
      obj(
          ConfigConstants.HTTP_HOST to "localhost",
          ConfigConstants.HTTP_PORT to port,
          ConfigConstants.HTTP_POST_MAX_SIZE to maxPostSize
      )
    }
    val options = deploymentOptionsOf(config)
    vertx.deployVerticle(HttpEndpoint::class.qualifiedName, options, ctx.completing())
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
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .sendAwait()
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
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        // with "Accept: application/json"
        val response1 = client.get(port, "localhost", "/")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .putHeader("Accept", "application/json")
            .sendAwait()
        assertThat(response1.body().map).containsKey("version")

        // with "Accept: text/html"
        val response2 = client.get(port, "localhost", "/")
            .`as`(BodyCodec.string())
            .expect(ResponsePredicate.SC_OK)
            .expect(contentType("text/html"))
            .putHeader("Accept", "text/html")
            .sendAwait()
        assertThat(response2.body()).contains("html")

        // with "Accept: */*"
        val response3 = client.get(port, "localhost", "/")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .putHeader("Accept", "*/*")
            .sendAwait()
        assertThat(response3.body().map).containsKey("version")

        // with complex "Accept" header
        val response4 = client.get(port, "localhost", "/")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .putHeader("Accept", "application/json,text/html")
            .sendAwait()
        assertThat(response4.body().map).containsKey("version")

        // with complex "Accept" header
        val response5 = client.get(port, "localhost", "/")
            .`as`(BodyCodec.string())
            .expect(ResponsePredicate.SC_OK)
            .expect(contentType("text/html"))
            .putHeader("Accept", "text/html,application/json")
            .sendAwait()
        assertThat(response5.body()).contains("html")

        // with complex "Accept" header
        val response6 = client.get(port, "localhost", "/")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .putHeader("Accept", "text/html;q=0.5,application/json;q=1.0")
            .sendAwait()
        assertThat(response6.body().map).containsKey("version")

        // without "Accept" header
        val response7 = client.get(port, "localhost", "/")
            .expect(ResponsePredicate.SC_OK)
            .sendAwait()
        assertThat(JsonObject(response7.body().toString(StandardCharsets.UTF_8)).map).containsKey("version")
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
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/services")
            .`as`(BodyCodec.jsonArray())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .sendAwait()

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
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        client.get(port, "localhost", "/services/UNKNOWN_ID")
            .expect(ResponsePredicate.SC_NOT_FOUND)
            .sendAwait()

        val response = client.get(port, "localhost", "/services/ID")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .sendAwait()

        assertThat(JsonUtils.fromJson<Service>(response.body()))
            .isEqualTo(serviceMetadata[0])

        ctx.completeNow()
      }
    }
  }

  /**
   * Test that the endpoint returns a list of workflows
   */
  @Test
  fun getWorkflows(vertx: Vertx, ctx: VertxTestContext) {
    val s1 = Submission(workflow = Workflow())
    coEvery { submissionRegistry.countProcessChains(s1.id) } returns 15
    coEvery { submissionRegistry.countProcessChains(s1.id,
        ProcessChainStatus.REGISTERED) } returns 1
    coEvery { submissionRegistry.countProcessChains(s1.id,
        ProcessChainStatus.RUNNING) } returns 2
    coEvery { submissionRegistry.countProcessChains(s1.id,
        ProcessChainStatus.CANCELLED) } returns 3
    coEvery { submissionRegistry.countProcessChains(s1.id,
        ProcessChainStatus.ERROR) } returns 4
    coEvery { submissionRegistry.countProcessChains(s1.id,
        ProcessChainStatus.SUCCESS) } returns 5

    val s2 = Submission(workflow = Workflow())
    coEvery { submissionRegistry.countProcessChains(s2.id) } returns 65
    coEvery { submissionRegistry.countProcessChains(s2.id,
        ProcessChainStatus.REGISTERED) } returns 11
    coEvery { submissionRegistry.countProcessChains(s2.id,
        ProcessChainStatus.RUNNING) } returns 12
    coEvery { submissionRegistry.countProcessChains(s2.id,
        ProcessChainStatus.CANCELLED) } returns 13
    coEvery { submissionRegistry.countProcessChains(s2.id,
        ProcessChainStatus.ERROR) } returns 14
    coEvery { submissionRegistry.countProcessChains(s2.id,
        ProcessChainStatus.SUCCESS) } returns 15

    val s3 = Submission(workflow = Workflow(), status = Submission.Status.SUCCESS)
    coEvery { submissionRegistry.countProcessChains(s3.id) } returns 1
    coEvery { submissionRegistry.countProcessChains(s3.id,
        ProcessChainStatus.REGISTERED) } returns 0
    coEvery { submissionRegistry.countProcessChains(s3.id,
        ProcessChainStatus.RUNNING) } returns 0
    coEvery { submissionRegistry.countProcessChains(s3.id,
        ProcessChainStatus.CANCELLED) } returns 0
    coEvery { submissionRegistry.countProcessChains(s3.id,
        ProcessChainStatus.ERROR) } returns 0
    coEvery { submissionRegistry.countProcessChains(s3.id,
        ProcessChainStatus.SUCCESS) } returns 1

    coEvery { metadataRegistry.findServices() } returns emptyList()

    coEvery { submissionRegistry.findSubmissions(any(), any(), any(), any()) } returns
        listOf(s1, s2, s3)
    coEvery { submissionRegistry.countSubmissions() } returns 3

    val client = WebClient.create(vertx)
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/workflows")
            .`as`(BodyCodec.jsonArray())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .sendAwait()

        assertThat(response.headers()["x-page-size"]).isEqualTo("10")
        assertThat(response.headers()["x-page-offset"]).isEqualTo("0")
        assertThat(response.headers()["x-page-total"]).isEqualTo("3")

        assertThat(response.body()).isEqualTo(json {
          array(
              obj(
                  "id" to s1.id,
                  "status" to Submission.Status.ACCEPTED.toString(),
                  "runningProcessChains" to 2,
                  "cancelledProcessChains" to 3,
                  "failedProcessChains" to 4,
                  "succeededProcessChains" to 5,
                  "totalProcessChains" to 15,
                  "requiredCapabilities" to array()
              ),
              obj(
                  "id" to s2.id,
                  "status" to Submission.Status.ACCEPTED.toString(),
                  "runningProcessChains" to 12,
                  "cancelledProcessChains" to 13,
                  "failedProcessChains" to 14,
                  "succeededProcessChains" to 15,
                  "totalProcessChains" to 65,
                  "requiredCapabilities" to array()
              ),
              obj(
                  "id" to s3.id,
                  "status" to Submission.Status.SUCCESS.toString(),
                  "runningProcessChains" to 0,
                  "cancelledProcessChains" to 0,
                  "failedProcessChains" to 0,
                  "succeededProcessChains" to 1,
                  "totalProcessChains" to 1,
                  "requiredCapabilities" to array()
              )
          )
        })
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that the endpoint returns a list of workflows with a given status
   */
  @Test
  fun getWorkflowsByStatus(vertx: Vertx, ctx: VertxTestContext) {
    val s3 = Submission(workflow = Workflow(), status = Submission.Status.SUCCESS)

    coEvery { submissionRegistry.countProcessChains(s3.id) } returns 1
    coEvery { submissionRegistry.countProcessChains(s3.id,
        ProcessChainStatus.REGISTERED) } returns 0
    coEvery { submissionRegistry.countProcessChains(s3.id,
        ProcessChainStatus.RUNNING) } returns 0
    coEvery { submissionRegistry.countProcessChains(s3.id,
        ProcessChainStatus.CANCELLED) } returns 0
    coEvery { submissionRegistry.countProcessChains(s3.id,
        ProcessChainStatus.ERROR) } returns 0
    coEvery { submissionRegistry.countProcessChains(s3.id,
        ProcessChainStatus.SUCCESS) } returns 1

    coEvery { metadataRegistry.findServices() } returns emptyList()

    coEvery { submissionRegistry.findSubmissions(Submission.Status.SUCCESS,
        any(), any(), any()) } returns listOf(s3)
    coEvery { submissionRegistry.countSubmissions(Submission.Status.SUCCESS) } returns 1

    val client = WebClient.create(vertx)
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/workflows?status=SUCCESS")
            .`as`(BodyCodec.jsonArray())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .sendAwait()

        assertThat(response.headers()["x-page-size"]).isEqualTo("10")
        assertThat(response.headers()["x-page-offset"]).isEqualTo("0")
        assertThat(response.headers()["x-page-total"]).isEqualTo("1")

        assertThat(response.body()).isEqualTo(json {
          array(
              obj(
                  "id" to s3.id,
                  "status" to Submission.Status.SUCCESS.toString(),
                  "runningProcessChains" to 0,
                  "cancelledProcessChains" to 0,
                  "failedProcessChains" to 0,
                  "succeededProcessChains" to 1,
                  "totalProcessChains" to 1,
                  "requiredCapabilities" to array()
              )
          )
        })
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that the endpoint returns a single workflow
   */
  @Test
  fun getWorkflowById(vertx: Vertx, ctx: VertxTestContext) {
    val s1 = Submission(workflow = Workflow())
    coEvery { submissionRegistry.countProcessChains(s1.id) } returns 15
    coEvery { submissionRegistry.countProcessChains(s1.id,
        ProcessChainStatus.REGISTERED) } returns 1
    coEvery { submissionRegistry.countProcessChains(s1.id,
        ProcessChainStatus.RUNNING) } returns 2
    coEvery { submissionRegistry.countProcessChains(s1.id,
        ProcessChainStatus.CANCELLED) } returns 3
    coEvery { submissionRegistry.countProcessChains(s1.id,
        ProcessChainStatus.ERROR) } returns 4
    coEvery { submissionRegistry.countProcessChains(s1.id,
        ProcessChainStatus.SUCCESS) } returns 5

    coEvery { metadataRegistry.findServices() } returns emptyList()

    coEvery { submissionRegistry.findSubmissionById(s1.id) } returns s1
    coEvery { submissionRegistry.findSubmissionById(neq(s1.id)) } returns null

    val client = WebClient.create(vertx)
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        client.get(port, "localhost", "/workflows/${s1.id}_doesnotexist")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_NOT_FOUND)
            .sendAwait()

        val response = client.get(port, "localhost", "/workflows/${s1.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .sendAwait()

        assertThat(response.body()).isEqualTo(json {
            obj(
                "id" to s1.id,
                "workflow" to JsonUtils.toJson(s1.workflow),
                "status" to Submission.Status.ACCEPTED.toString(),
                "runningProcessChains" to 2,
                "cancelledProcessChains" to 3,
                "failedProcessChains" to 4,
                "succeededProcessChains" to 5,
                "totalProcessChains" to 15,
                "requiredCapabilities" to array()
            )
        })
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
        ), input = Variable(), enumerator = Variable())
    )))
    coEvery { submissionRegistry.countProcessChains(s1.id) } returns 1
    coEvery { submissionRegistry.countProcessChains(s1.id,
        ProcessChainStatus.REGISTERED) } returns 1
    coEvery { submissionRegistry.countProcessChains(s1.id,
        ProcessChainStatus.RUNNING) } returns 0
    coEvery { submissionRegistry.countProcessChains(s1.id,
        ProcessChainStatus.CANCELLED) } returns 0
    coEvery { submissionRegistry.countProcessChains(s1.id,
        ProcessChainStatus.ERROR) } returns 0
    coEvery { submissionRegistry.countProcessChains(s1.id,
        ProcessChainStatus.SUCCESS) } returns 0

    coEvery { metadataRegistry.findServices() } returns listOf(
        Service(id = "a", name = "name a", description = "", path = "",
            runtime = "", parameters = emptyList(),
            requiredCapabilities = setOf("cap1", "cap2")),
        Service(id = "b", name = "name b", description = "", path = "",
            runtime = "", parameters = emptyList(),
            requiredCapabilities = setOf("cap1", "cap3"))
    )

    coEvery { submissionRegistry.findSubmissionById(s1.id) } returns s1

    val client = WebClient.create(vertx)
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/workflows/${s1.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .sendAwait()

        assertThat(response.body()).isEqualTo(json {
          obj(
              "id" to s1.id,
              "workflow" to JsonUtils.toJson(s1.workflow),
              "status" to Submission.Status.ACCEPTED.toString(),
              "runningProcessChains" to 0,
              "cancelledProcessChains" to 0,
              "failedProcessChains" to 0,
              "succeededProcessChains" to 0,
              "totalProcessChains" to 1,
              "requiredCapabilities" to array("cap1", "cap2", "cap3")
          )
        })
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
    coEvery { submissionRegistry.countProcessChains(s1.id) } returns 1
    coEvery { submissionRegistry.countProcessChains(s1.id,
        ProcessChainStatus.REGISTERED) } returns 0
    coEvery { submissionRegistry.countProcessChains(s1.id,
        ProcessChainStatus.RUNNING) } returns 0
    coEvery { submissionRegistry.countProcessChains(s1.id,
        ProcessChainStatus.CANCELLED) } returns 0
    coEvery { submissionRegistry.countProcessChains(s1.id,
        ProcessChainStatus.ERROR) } returns 0
    coEvery { submissionRegistry.countProcessChains(s1.id,
        ProcessChainStatus.SUCCESS) } returns 1

    coEvery { metadataRegistry.findServices() } returns emptyList()

    coEvery { submissionRegistry.findSubmissionById(s1.id) } returns s1

    val results = mapOf("output_file1" to listOf("/out/test.txt"))
    coEvery { submissionRegistry.getSubmissionResults(s1.id) } returns results

    val client = WebClient.create(vertx)
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/workflows/${s1.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .sendAwait()

        assertThat(response.body()).isEqualTo(json {
          obj(
              "id" to s1.id,
              "workflow" to JsonUtils.toJson(s1.workflow),
              "status" to Submission.Status.SUCCESS.toString(),
              "runningProcessChains" to 0,
              "cancelledProcessChains" to 0,
              "failedProcessChains" to 0,
              "succeededProcessChains" to 1,
              "totalProcessChains" to 1,
              "results" to JsonUtils.toJson(results),
              "requiredCapabilities" to array()
          )
        })
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
    coEvery { submissionRegistry.countProcessChains(s1.id) } returns 1
    coEvery { submissionRegistry.countProcessChains(s1.id,
        ProcessChainStatus.REGISTERED) } returns 0
    coEvery { submissionRegistry.countProcessChains(s1.id,
        ProcessChainStatus.RUNNING) } returns 0
    coEvery { submissionRegistry.countProcessChains(s1.id,
        ProcessChainStatus.CANCELLED) } returns 0
    coEvery { submissionRegistry.countProcessChains(s1.id,
        ProcessChainStatus.ERROR) } returns 1
    coEvery { submissionRegistry.countProcessChains(s1.id,
        ProcessChainStatus.SUCCESS) } returns 0

    coEvery { metadataRegistry.findServices() } returns emptyList()

    coEvery { submissionRegistry.findSubmissionById(s1.id) } returns s1

    val errorMessage = "This is an error message"
    coEvery { submissionRegistry.getSubmissionErrorMessage(s1.id) } returns errorMessage

    val client = WebClient.create(vertx)
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/workflows/${s1.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .sendAwait()

        assertThat(response.body()).isEqualTo(json {
          obj(
              "id" to s1.id,
              "workflow" to JsonUtils.toJson(s1.workflow),
              "status" to Submission.Status.ERROR.toString(),
              "runningProcessChains" to 0,
              "cancelledProcessChains" to 0,
              "failedProcessChains" to 1,
              "succeededProcessChains" to 0,
              "totalProcessChains" to 1,
              "errorMessage" to errorMessage,
              "requiredCapabilities" to array()
          )
        })
      }

      ctx.completeNow()
    }
  }

  /**
   * Test if a workflow can be updated
   */
  @Test
  fun putWorkflowById(vertx: Vertx, ctx: VertxTestContext) {
    val s1 = Submission(workflow = Workflow(), status = Submission.Status.RUNNING)

    coEvery { submissionRegistry.findSubmissionById(s1.id) } returns s1
    coEvery { submissionRegistry.findSubmissionById("UNKNOWN") } returns null
    coEvery { submissionRegistry.findProcessChainIdsBySubmissionIdAndStatus(
        s1.id, ProcessChainStatus.RUNNING) } returns emptyList()
    coEvery { submissionRegistry.getSubmissionStatus(s1.id) } returns
        Submission.Status.RUNNING andThen Submission.Status.CANCELLED

    val client = WebClient.create(vertx)
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val cancelledBody = json {
          obj(
              "status" to "CANCELLED"
          )
        }

        val missingBody = json {
          obj(
              "foo" to "bar"
          )
        }

        val invalidBody = json {
          obj(
              "status" to "INVALID"
          )
        }

        // test invalid requests
        client.put(port, "localhost", "/workflows/${s1.id}")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .sendAwait()

        client.put(port, "localhost", "/workflows/${s1.id}")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .sendBufferAwait(Buffer.buffer("INVALID BODY"))

        client.put(port, "localhost", "/workflows/${s1.id}")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .sendJsonObjectAwait(missingBody)

        client.put(port, "localhost", "/workflows/${s1.id}")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .sendJsonObjectAwait(invalidBody)

        client.put(port, "localhost", "/workflows/UNKNOWN")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_NOT_FOUND)
            .sendJsonObjectAwait(cancelledBody)

        client.put(port, "localhost", "/workflows/${s1.id}")
            .`as`(BodyCodec.none())
            .putHeader("accept", "text/html")
            .expect(ResponsePredicate.SC_NOT_FOUND)
            .sendJsonObjectAwait(cancelledBody)

        // now test valid requests
        coEvery { submissionRegistry.setAllProcessChainsStatus(s1.id,
            ProcessChainStatus.REGISTERED, ProcessChainStatus.CANCELLED) } just Runs
        coEvery { submissionRegistry.countProcessChains(s1.id,
            ProcessChainStatus.RUNNING) } returns 1
        coEvery { submissionRegistry.countProcessChains(s1.id,
            ProcessChainStatus.CANCELLED) } returns 2
        coEvery { submissionRegistry.countProcessChains(s1.id,
            ProcessChainStatus.SUCCESS) } returns 0
        coEvery { submissionRegistry.countProcessChains(s1.id,
            ProcessChainStatus.ERROR) } returns 0
        coEvery { submissionRegistry.countProcessChains(s1.id) } returns 3
        val response1 = client.put(port, "localhost", "/workflows/${s1.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .sendJsonObjectAwait(cancelledBody)

        assertThat(response1.body()).isEqualTo(json {
          obj(
              "id" to s1.id,
              "status" to Submission.Status.RUNNING.toString(),
              "runningProcessChains" to 1,
              "cancelledProcessChains" to 2,
              "succeededProcessChains" to 0,
              "failedProcessChains" to 0,
              "totalProcessChains" to 3
          )
        })

        coVerify(exactly = 1) {
          submissionRegistry.setAllProcessChainsStatus(s1.id,
              ProcessChainStatus.REGISTERED, ProcessChainStatus.CANCELLED)
        }
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
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        client.post(port, "localhost", "/workflows")
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .sendAwait()
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
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        client.post(port, "localhost", "/workflows")
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .sendJsonObjectAwait(JsonObject().put("invalid", true))
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
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        client.post(port, "localhost", "/workflows")
            .expect(ResponsePredicate.SC_REQUEST_ENTITY_TOO_LARGE)
            .sendJsonObjectAwait(JsonUtils.toJson(w))
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
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        client.post(port, "localhost", "/workflows")
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .sendJsonObjectAwait(JsonUtils.toJson(w))
      }
      ctx.completeNow()
    }
  }

  private fun doPostWorkflow(vertx: Vertx, ctx: VertxTestContext, body: Buffer,
      expectedWorkflow: Workflow) {
    val submissionSlot = slot<Submission>()
    coEvery { submissionRegistry.addSubmission(capture(submissionSlot)) } answers {
      ctx.verify {
        assertThat(submissionSlot.captured.id).isNotNull
        assertThat(submissionSlot.captured.status).isEqualTo(Submission.Status.ACCEPTED)
        assertThat(submissionSlot.captured.workflow).isEqualTo(expectedWorkflow)
      }
    }

    val client = WebClient.create(vertx)
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val response = client.post(port, "localhost", "/workflows")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_ACCEPTED)
            .expect(ResponsePredicate.JSON)
            .sendBufferAwait(body)

        assertThat(response.body()).isEqualTo(json {
          obj(
              "id" to submissionSlot.captured.id,
              "workflow" to JsonUtils.toJson(expectedWorkflow),
              "status" to Submission.Status.ACCEPTED.toString()
          )
        })
      }

      ctx.completeNow()
    }
  }

  /**
   * Test that a workflow can be successfully posted
   */
  @Test
  fun postWorkflow(vertx: Vertx, ctx: VertxTestContext) {
    val expected = Workflow()
    val buf = Buffer.buffer(JsonUtils.writeValueAsString(expected))
    doPostWorkflow(vertx, ctx, buf, expected)
  }

  /**
   * Test that a workflow can be successfully posted as YAML
   */
  @Test
  fun postWorkflowYaml(vertx: Vertx, ctx: VertxTestContext) {
    val expected = Workflow()
    val buf = Buffer.buffer(YamlUtils.mapper.writeValueAsString(expected))
    doPostWorkflow(vertx, ctx, buf, expected)
  }

  /**
   * Test if the endpoint still accepts workflows with a store action
   */
  @Test
  fun postWorkflowWithStore(vertx: Vertx, ctx: VertxTestContext) {
    val body = json {
      obj(
          "api" to "4.3.0",
          "vars" to array(
              obj(
                  "id" to "input_file1",
                  "value" to "input_file.txt"
              ),
              obj(
                  "id" to "output_file1"
              ),
              obj(
                  "id" to "i"
              )
          ),
          "actions" to array(
              obj(
                  "id" to "cp1",
                  "type" to "execute",
                  "service" to "cp",
                  "inputs" to array(
                      obj(
                          "id" to "input_file",
                          "var" to "input_file1"
                      )
                  ),
                  "outputs" to array(
                      obj(
                          "id" to "output_file",
                          "var" to "output_file1"
                      )
                  )
              ),
              obj(
                  "type" to "store",
                  "inputs" to array("output_file1")
              ),
              obj(
                  "id" to "foreach1",
                  "type" to "for",
                  "input" to "input_file1",
                  "enumerator" to "i",
                  "actions" to array(
                      obj(
                          "type" to "store",
                          "inputs" to array("output_file1")
                      )
                  )
              )
          )
      )
    }

    val inputFile1 = Variable(id = "input_file1", value = "input_file.txt")
    val outputFile1 = Variable(id = "output_file1")
    val enumerator1 = Variable(id = "i")
    val expected = Workflow(
        vars = listOf(
            inputFile1,
            outputFile1,
            enumerator1
        ),
        actions = listOf(
            ExecuteAction(
                id = "cp1",
                service = "cp",
                inputs = listOf(GenericParameter(
                    id = "input_file",
                    variable = inputFile1
                )),
                outputs = listOf(OutputParameter(
                    id = "output_file",
                    variable = outputFile1
                ))
            ),
            ForEachAction(
                id = "foreach1",
                input = inputFile1,
                enumerator = enumerator1
            )
        )
    )

    val buf = Buffer.buffer(JsonUtils.writeValueAsString(body))
    doPostWorkflow(vertx, ctx, buf, expected)
  }

  /**
   * Test if the endpoint still accepts workflows with a action parameters
   */
  @Test
  fun postWorkflowWithActionParameters(vertx: Vertx, ctx: VertxTestContext) {
    val body = json {
      obj(
          "api" to "4.3.0",
          "vars" to array(
              obj(
                  "id" to "input_file1",
                  "value" to "input_file.txt"
              ),
              obj(
                  "id" to "output_file1"
              ),
              obj(
                  "id" to "i"
              ),
              obj(
                  "id" to "param1",
                  "value" to true
              ),
              obj(
                  "id" to "param2",
                  "value" to false
              )
          ),
          "actions" to array(
              obj(
                  "id" to "cp1",
                  "type" to "execute",
                  "service" to "cp",
                  "inputs" to array(
                      obj(
                          "id" to "input_file",
                          "var" to "input_file1"
                      )
                  ),
                  "outputs" to array(
                      obj(
                          "id" to "output_file",
                          "var" to "output_file1"
                      )
                  ),
                  "parameters" to array(
                      obj(
                          "id" to "no_overwrite",
                          "var" to "param1"
                      )
                  )
              ),
              obj(
                  "id" to "foreach1",
                  "type" to "for",
                  "input" to "input_file1",
                  "enumerator" to "i",
                  "actions" to array(
                      obj(
                          "id" to "cp2",
                          "type" to "execute",
                          "service" to "cp",
                          "inputs" to array(
                              obj(
                                  "id" to "input_file",
                                  "var" to "input_file1"
                              )
                          ),
                          "outputs" to array(
                              obj(
                                  "id" to "output_file",
                                  "var" to "output_file1"
                              )
                          ),
                          "parameters" to array(
                              obj(
                                  "id" to "no_overwrite",
                                  "var" to "param2"
                              )
                          )
                      )
                  )
              )
          )
      )
    }

    val inputFile1 = Variable(id = "input_file1", value = "input_file.txt")
    val outputFile1 = Variable(id = "output_file1")
    val enumerator1 = Variable(id = "i")
    val param1 = Variable(id = "param1", value = true)
    val param2 = Variable(id = "param2", value = false)
    val expected = Workflow(
        vars = listOf(
            inputFile1,
            outputFile1,
            enumerator1,
            param1,
            param2
        ),
        actions = listOf(
            ExecuteAction(
                id = "cp1",
                service = "cp",
                inputs = listOf(GenericParameter(
                    id = "input_file",
                    variable = inputFile1
                ), GenericParameter(
                    id = "no_overwrite",
                    variable = param1
                )),
                outputs = listOf(OutputParameter(
                    id = "output_file",
                    variable = outputFile1
                ))
            ),
            ForEachAction(
                id = "foreach1",
                input = inputFile1,
                enumerator = enumerator1,
                actions = listOf(
                    ExecuteAction(
                        id = "cp2",
                        service = "cp",
                        inputs = listOf(GenericParameter(
                            id = "input_file",
                            variable = inputFile1
                        ), GenericParameter(
                            id = "no_overwrite",
                            variable = param2
                        )),
                        outputs = listOf(OutputParameter(
                            id = "output_file",
                            variable = outputFile1
                        ))
                    )
                )
            )
        )
    )

    val buf = Buffer.buffer(JsonUtils.writeValueAsString(body))
    doPostWorkflow(vertx, ctx, buf, expected)
  }

  /**
   * Test that the endpoint returns a list of all process chains (without executables)
   */
  @Test
  fun getProcessChains(vertx: Vertx, ctx: VertxTestContext) {
    val s1 = Submission(workflow = Workflow())
    val s2 = Submission(workflow = Workflow())
    coEvery { submissionRegistry.findSubmissions() } returns listOf(s1, s2)

    val pc1 = ProcessChain(executables = listOf(Executable(path = "path", arguments = emptyList())))
    val pc2 = ProcessChain()
    val pc3 = ProcessChain()
    val pc4 = ProcessChain()

    coEvery { submissionRegistry.findProcessChains(any(), any(), any(), any(), any()) } returns listOf(
        Pair(pc1, s1.id), Pair(pc2, s1.id), Pair(pc3, s2.id), Pair(pc4, s2.id))
    coEvery { submissionRegistry.countProcessChains() } returns 4

    coEvery { submissionRegistry.getProcessChainStatus(pc1.id) } returns ProcessChainStatus.SUCCESS
    coEvery { submissionRegistry.getProcessChainStatus(pc2.id) } returns ProcessChainStatus.RUNNING
    coEvery { submissionRegistry.getProcessChainStatus(pc3.id) } returns ProcessChainStatus.REGISTERED
    coEvery { submissionRegistry.getProcessChainStatus(pc4.id) } returns ProcessChainStatus.ERROR

    val startTime = Instant.now()
    coEvery { submissionRegistry.getProcessChainStartTime(pc1.id) } returns startTime
    coEvery { submissionRegistry.getProcessChainStartTime(pc2.id) } returns startTime
    coEvery { submissionRegistry.getProcessChainStartTime(pc3.id) } returns null
    coEvery { submissionRegistry.getProcessChainStartTime(pc4.id) } returns startTime

    val endTime = Instant.now()
    coEvery { submissionRegistry.getProcessChainEndTime(pc1.id) } returns endTime
    coEvery { submissionRegistry.getProcessChainEndTime(pc2.id) } returns null
    coEvery { submissionRegistry.getProcessChainEndTime(pc3.id) } returns null
    coEvery { submissionRegistry.getProcessChainEndTime(pc4.id) } returns endTime

    coEvery { submissionRegistry.getProcessChainResults(pc1.id) } returns mapOf(
        "output_file1" to listOf("output.txt"))
    coEvery { submissionRegistry.getProcessChainResults(pc2.id) } returns mapOf(
        "output_file_that_should_not_be_returned" to listOf("output2.txt"))
    coEvery { submissionRegistry.getProcessChainResults(pc3.id) } returns null
    coEvery { submissionRegistry.getProcessChainResults(pc4.id) } returns null

    coEvery { submissionRegistry.getProcessChainErrorMessage(pc1.id) } returns
        "This error SHOULD NOT BE returned"
    coEvery { submissionRegistry.getProcessChainErrorMessage(pc2.id) } returns null
    coEvery { submissionRegistry.getProcessChainErrorMessage(pc3.id) } returns null
    coEvery { submissionRegistry.getProcessChainErrorMessage(pc4.id) } returns
        "THIS is an ERROR"

    val client = WebClient.create(vertx)
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/processchains")
            .`as`(BodyCodec.jsonArray())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .sendAwait()

        assertThat(response.headers()["x-page-size"]).isEqualTo("10")
        assertThat(response.headers()["x-page-offset"]).isEqualTo("0")
        assertThat(response.headers()["x-page-total"]).isEqualTo("4")

        assertThat(response.body()).isEqualTo(json {
          array(
              obj(
                  "id" to pc1.id,
                  "requiredCapabilities" to array(),
                  "submissionId" to s1.id,
                  "status" to "SUCCESS",
                  "startTime" to startTime,
                  "endTime" to endTime
              ),
              obj(
                  "id" to pc2.id,
                  "requiredCapabilities" to array(),
                  "submissionId" to s1.id,
                  "status" to "RUNNING",
                  "startTime" to startTime
              ),
              obj(
                  "id" to pc3.id,
                  "requiredCapabilities" to array(),
                  "submissionId" to s2.id,
                  "status" to "REGISTERED"
              ),
              obj(
                  "id" to pc4.id,
                  "requiredCapabilities" to array(),
                  "submissionId" to s2.id,
                  "status" to "ERROR",
                  "startTime" to startTime,
                  "endTime" to endTime,
                  "errorMessage" to "THIS is an ERROR"
              )
          )
        })
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
    coEvery { submissionRegistry.findSubmissions() } returns listOf(s1, s2)

    val pc1 = ProcessChain(executables = listOf(Executable(path = "path", arguments = emptyList())))
    val pc2 = ProcessChain()

    coEvery { submissionRegistry.findProcessChains(s1.id, null, 10, 0, -1) } returns
        listOf(pc1 to s1.id, pc2 to s1.id)
    coEvery { submissionRegistry.countProcessChains(s1.id) } returns 2

    coEvery { submissionRegistry.getProcessChainStatus(pc1.id) } returns ProcessChainStatus.SUCCESS
    coEvery { submissionRegistry.getProcessChainStatus(pc2.id) } returns ProcessChainStatus.RUNNING

    val startTime = Instant.now()
    coEvery { submissionRegistry.getProcessChainStartTime(pc1.id) } returns startTime
    coEvery { submissionRegistry.getProcessChainStartTime(pc2.id) } returns startTime

    val endTime = Instant.now()
    coEvery { submissionRegistry.getProcessChainEndTime(pc1.id) } returns endTime
    coEvery { submissionRegistry.getProcessChainEndTime(pc2.id) } returns null

    coEvery { submissionRegistry.getProcessChainResults(pc1.id) } returns mapOf(
        "output_file1" to listOf("output.txt"))
    coEvery { submissionRegistry.getProcessChainResults(pc2.id) } returns null

    val client = WebClient.create(vertx)
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/processchains?submissionId=${s1.id}")
            .`as`(BodyCodec.jsonArray())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .sendAwait()

        assertThat(response.headers()["x-page-size"]).isEqualTo("10")
        assertThat(response.headers()["x-page-offset"]).isEqualTo("0")
        assertThat(response.headers()["x-page-total"]).isEqualTo("2")

        assertThat(response.body()).isEqualTo(json {
          array(
              obj(
                  "id" to pc1.id,
                  "requiredCapabilities" to array(),
                  "submissionId" to s1.id,
                  "status" to "SUCCESS",
                  "startTime" to startTime,
                  "endTime" to endTime
              ),
              obj(
                  "id" to pc2.id,
                  "requiredCapabilities" to array(),
                  "submissionId" to s1.id,
                  "status" to "RUNNING",
                  "startTime" to startTime
              )
          )
        })
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
    coEvery { submissionRegistry.findSubmissions() } returns listOf(s1, s2)

    val pc1 = ProcessChain(executables = listOf(Executable(path = "path", arguments = emptyList())))

    coEvery { submissionRegistry.findProcessChains(s1.id, ProcessChainStatus.SUCCESS,
        10, 0, -1) } returns listOf(pc1 to s1.id)
    coEvery { submissionRegistry.countProcessChains(s1.id, ProcessChainStatus.SUCCESS) } returns 1

    coEvery { submissionRegistry.getProcessChainStatus(pc1.id) } returns ProcessChainStatus.SUCCESS

    val startTime = Instant.now()
    coEvery { submissionRegistry.getProcessChainStartTime(pc1.id) } returns startTime

    val endTime = Instant.now()
    coEvery { submissionRegistry.getProcessChainEndTime(pc1.id) } returns endTime

    coEvery { submissionRegistry.getProcessChainResults(pc1.id) } returns mapOf(
        "output_file1" to listOf("output.txt"))

    val client = WebClient.create(vertx)
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val response = client.get(port, "localhost",
              "/processchains?submissionId=${s1.id}&status=SUCCESS")
            .`as`(BodyCodec.jsonArray())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .sendAwait()

        assertThat(response.headers()["x-page-size"]).isEqualTo("10")
        assertThat(response.headers()["x-page-offset"]).isEqualTo("0")
        assertThat(response.headers()["x-page-total"]).isEqualTo("1")

        assertThat(response.body()).isEqualTo(json {
          array(
              obj(
                  "id" to pc1.id,
                  "requiredCapabilities" to array(),
                  "submissionId" to s1.id,
                  "status" to "SUCCESS",
                  "startTime" to startTime,
                  "endTime" to endTime
              )
          )
        })
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
        path = "path", arguments = emptyList())))

    coEvery { submissionRegistry.findProcessChainById(pc1.id) } returns pc1
    coEvery { submissionRegistry.findProcessChainById(neq(pc1.id)) } returns null
    coEvery { submissionRegistry.getProcessChainSubmissionId(pc1.id) } returns sid
    coEvery { submissionRegistry.getProcessChainStatus(pc1.id) } returns
        ProcessChainStatus.SUCCESS
    val startTime = Instant.now()
    coEvery { submissionRegistry.getProcessChainStartTime(pc1.id) } returns startTime
    val endTime = Instant.now()
    coEvery { submissionRegistry.getProcessChainEndTime(pc1.id) } returns endTime
    coEvery { submissionRegistry.getProcessChainResults(pc1.id) } returns mapOf(
        "output_file1" to listOf("output.txt"))

    val client = WebClient.create(vertx)
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        client.get(port, "localhost", "/processchains/${pc1.id}_doesnotexist")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_NOT_FOUND)
            .sendAwait()

        val response = client.get(port, "localhost", "/processchains/${pc1.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .sendAwait()

        assertThat(response.body()).isEqualTo(json {
          obj(
              "id" to pc1.id,
              "executables" to array(
                  obj(
                      "id" to eid,
                      "path" to "path",
                      "arguments" to array(),
                      "runtime" to "other",
                      "runtimeArgs" to array()
                  )
              ),
              "requiredCapabilities" to array(),
              "submissionId" to sid,
              "status" to ProcessChainStatus.SUCCESS.toString(),
              "startTime" to startTime,
              "endTime" to endTime,
              "results" to obj(
                  "output_file1" to array("output.txt")
              )
          )
        })
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
        path = "path", arguments = emptyList())))

    coEvery { submissionRegistry.findProcessChainById(pc1.id) } returns pc1
    coEvery { submissionRegistry.findProcessChainById(neq(pc1.id)) } returns null
    coEvery { submissionRegistry.getProcessChainSubmissionId(pc1.id) } returns sid
    coEvery { submissionRegistry.getProcessChainStatus(pc1.id) } returns
        ProcessChainStatus.RUNNING
    val startTime = Instant.now()
    coEvery { submissionRegistry.getProcessChainStartTime(pc1.id) } returns startTime

    val address = LOCAL_AGENT_ADDRESS_PREFIX + pc1.id
    vertx.eventBus().consumer<JsonObject>(address).handler { msg ->
      if (msg.body().getString("action") == "getProgress") {
        msg.reply(expectedProgress)
      }
    }

    val client = WebClient.create(vertx)
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/processchains/${pc1.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .sendAwait()

        assertThat(response.body()).isEqualTo(json {
          obj(
              "id" to pc1.id,
              "executables" to array(
                  obj(
                      "id" to eid,
                      "path" to "path",
                      "arguments" to array(),
                      "runtime" to "other",
                      "runtimeArgs" to array()
                  )
              ),
              "requiredCapabilities" to array(),
              "submissionId" to sid,
              "status" to ProcessChainStatus.RUNNING.toString(),
              "startTime" to startTime,
              "estimatedProgress" to expectedProgress
          )
        })
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

    coEvery { submissionRegistry.findProcessChainById(pc1.id) } returns pc1
    coEvery { submissionRegistry.findProcessChainById(pc2.id) } returns pc2
    coEvery { submissionRegistry.findProcessChainById(pc3.id) } returns pc3
    coEvery { submissionRegistry.findProcessChainById("UNKNOWN") } returns null

    coEvery { submissionRegistry.getProcessChainSubmissionId(pc1.id) } returns sid
    coEvery { submissionRegistry.getProcessChainSubmissionId(pc2.id) } returns sid
    coEvery { submissionRegistry.getProcessChainSubmissionId(pc3.id) } returns sid

    val startTime = Instant.now()
    coEvery { submissionRegistry.getProcessChainStartTime(pc1.id) } returns startTime
    coEvery { submissionRegistry.getProcessChainStartTime(pc2.id) } returns startTime
    coEvery { submissionRegistry.getProcessChainStartTime(pc3.id) } returns null

    val endTime = Instant.now()
    coEvery { submissionRegistry.getProcessChainEndTime(pc1.id) } returns endTime
    coEvery { submissionRegistry.getProcessChainEndTime(pc2.id) } returns null
    coEvery { submissionRegistry.getProcessChainEndTime(pc3.id) } returns null

    val client = WebClient.create(vertx)
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val cancelledBody = json {
          obj(
              "status" to "CANCELLED"
          )
        }

        val missingBody = json {
          obj(
              "foo" to "bar"
          )
        }

        val invalidBody = json {
          obj(
              "status" to "INVALID"
          )
        }

        // test invalid requests
        client.put(port, "localhost", "/processchains/${pc1.id}")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .sendAwait()

        client.put(port, "localhost", "/processchains/${pc1.id}")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .sendBufferAwait(Buffer.buffer("INVALID BODY"))

        client.put(port, "localhost", "/processchains/${pc1.id}")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .sendJsonObjectAwait(missingBody)

        client.put(port, "localhost", "/processchains/${pc1.id}")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .sendJsonObjectAwait(invalidBody)

        client.put(port, "localhost", "/processchains/UNKNOWN")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_NOT_FOUND)
            .sendJsonObjectAwait(cancelledBody)

        client.put(port, "localhost", "/processchains/${pc1.id}")
            .`as`(BodyCodec.none())
            .putHeader("accept", "text/html")
            .expect(ResponsePredicate.SC_NOT_FOUND)
            .sendJsonObjectAwait(cancelledBody)

        // now test valid requests
        coEvery { submissionRegistry.getProcessChainStatus(pc1.id) } returns
            ProcessChainStatus.SUCCESS
        val response1 = client.put(port, "localhost", "/processchains/${pc1.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .sendJsonObjectAwait(cancelledBody)

        assertThat(response1.body()).isEqualTo(json {
          obj(
              "id" to pc1.id,
              "requiredCapabilities" to array(),
              "submissionId" to sid,
              "status" to ProcessChainStatus.SUCCESS.toString(),
              "startTime" to startTime,
              "endTime" to endTime
          )
        })

        coEvery { submissionRegistry.getProcessChainStatus(pc2.id) } returns
            ProcessChainStatus.RUNNING
        val response2 = client.put(port, "localhost", "/processchains/${pc2.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .sendJsonObjectAwait(cancelledBody)

        assertThat(response2.body()).isEqualTo(json {
          obj(
              "id" to pc2.id,
              "requiredCapabilities" to array(),
              "submissionId" to sid,
              "status" to ProcessChainStatus.RUNNING.toString(),
              "startTime" to startTime
          )
        })

        coEvery { submissionRegistry.getProcessChainStatus(pc3.id) } returns
            ProcessChainStatus.REGISTERED andThen ProcessChainStatus.CANCELLED
        coEvery { submissionRegistry.setProcessChainStatus(pc3.id,
            ProcessChainStatus.REGISTERED, ProcessChainStatus.CANCELLED) } just Runs
        val response3 = client.put(port, "localhost", "/processchains/${pc3.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_OK)
            .sendJsonObjectAwait(cancelledBody)

        assertThat(response3.body()).isEqualTo(json {
          obj(
              "id" to pc3.id,
              "requiredCapabilities" to array(),
              "submissionId" to sid,
              "status" to ProcessChainStatus.CANCELLED.toString()
          )
        })
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
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/vms")
            .`as`(BodyCodec.jsonArray())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .sendAwait()

        assertThat(response.headers()["x-page-size"]).isEqualTo("10")
        assertThat(response.headers()["x-page-offset"]).isEqualTo("0")
        assertThat(response.headers()["x-page-total"]).isEqualTo("3")

        assertThat(response.body()).isEqualTo(json {
          array(
              obj(
                  "id" to vm1.id,
                  "setup" to JsonUtils.toJson(setup),
                  "status" to VM.Status.CREATING.toString()
              ),
              obj(
                  "id" to vm2.id,
                  "setup" to JsonUtils.toJson(setup),
                  "status" to VM.Status.PROVISIONING.toString()
              ),
              obj(
                  "id" to vm3.id,
                  "setup" to JsonUtils.toJson(setup),
                  "status" to VM.Status.RUNNING.toString()
              )
          )
        })
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
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/vms?status=CREATING")
            .`as`(BodyCodec.jsonArray())
            .expect(ResponsePredicate.SC_OK)
            .expect(ResponsePredicate.JSON)
            .sendAwait()

        assertThat(response.headers()["x-page-size"]).isEqualTo("10")
        assertThat(response.headers()["x-page-offset"]).isEqualTo("0")
        assertThat(response.headers()["x-page-total"]).isEqualTo("2")

        assertThat(response.body()).isEqualTo(json {
          array(
              obj(
                  "id" to vm1.id,
                  "setup" to JsonUtils.toJson(setup),
                  "status" to VM.Status.CREATING.toString()
              ),
              obj(
                  "id" to vm2.id,
                  "setup" to JsonUtils.toJson(setup),
                  "status" to VM.Status.CREATING.toString()
              )
          )
        })
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
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        client.get(port, "localhost", "/logs/processchains/$id")
            .expect(ResponsePredicate.SC_NOT_FOUND)
            .sendAwait()
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

    coEvery { agentRegistry.getPrimaryAgentIds() } returns emptySet()

    val client = WebClient.create(vertx)
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        client.get(port, "localhost", "/logs/processchains/$id")
            .expect(ResponsePredicate.SC_NOT_FOUND)
            .sendAwait()
      }
      ctx.completeNow()
    }
  }

  private fun prepareGetProcessChainLogById(vertx: Vertx, ctx: VertxTestContext,
      id: String, contents: String, start: Int = 0, end: Int = contents.length,
      agent1Asked: AtomicInteger, agent2Asked: AtomicInteger,
      checkOnly: Boolean = false, errorMessage: String? = null) {
    val agentId1 = "agent1"
    val agentId2 = "agent2"

    coEvery { submissionRegistry.getProcessChainStatus(id) } returns
        ProcessChainStatus.REGISTERED

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
        assertThat(replyAddress).matches(Pattern.quote("$address1.reply.") + ".+")
        if (end < start) {
          vertx.eventBus().send(replyAddress, json {
            obj(
                "error" to 416
            )
          })
        } else {
          vertx.eventBus().send(replyAddress, json {
            obj(
                "error" to 404
            )
          })
        }
      }
    }

    // create an agent that returns the log file
    val address2 = REMOTE_AGENT_ADDRESS_PREFIX + agentId2 +
        REMOTE_AGENT_PROCESSCHAINLOGS_SUFFIX
    vertx.eventBus().consumer<JsonObject>(address2) { msg ->
      agent2Asked.getAndIncrement()
      val obj = msg.body()
      GlobalScope.launch(vertx.dispatcher()) {
        ctx.coVerify {
          if (start != 0) {
            assertThat(obj.getLong("start")).isEqualTo(start.toLong())
          }
          if (end != contents.length) {
            assertThat(obj.getLong("end")).isEqualTo(end.toLong() - 1L)
          }
          assertThat(obj.getString("id")).isEqualTo(id)
          val replyAddress = obj.getString("replyAddress")
          assertThat(replyAddress).matches(Pattern.quote("$address2.reply.") + ".+")

          if (errorMessage != null) {
            vertx.eventBus().send(replyAddress, json {
              obj(
                  "error" to 500,
                  "message" to errorMessage
              )
            })
          } else {
            vertx.eventBus().requestAwait<Unit>(replyAddress, json {
              obj(
                  "size" to contents.length.toLong(),
                  "start" to start.toLong(),
                  "end" to end.toLong() - 1L,
                  "length" to (end - start).toLong()
              )
            })

            if (!checkOnly) {
              val chunk = json {
                obj(
                    "data" to contents.substring(start, end)
                )
              }
              vertx.eventBus().requestAwait<Unit>(replyAddress, chunk)
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
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/logs/processchains/$id")
            .`as`(BodyCodec.string())
            .expect(ResponsePredicate.SC_OK)
            .expect(contentType("text/plain"))
            .sendAwait()

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
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/logs/processchains/$id")
            .`as`(BodyCodec.string())
            .putHeader("Range", "bytes=2-2")
            .expect(ResponsePredicate.SC_PARTIAL_CONTENT)
            .expect(contentType("text/plain"))
            .sendAwait()

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
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/logs/processchains/$id")
            .`as`(BodyCodec.string())
            .putHeader("X-Range", "bytes=2-2")
            .expect(ResponsePredicate.SC_OK)
            .expect(contentType("text/plain"))
            .sendAwait()

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
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val response = client.head(port, "localhost", "/logs/processchains/$id")
            .`as`(BodyCodec.string())
            .expect(ResponsePredicate.SC_OK)
            .sendAwait()

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
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val response = client.head(port, "localhost", "/logs/processchains/$id")
            .`as`(BodyCodec.string())
            .expect(ResponsePredicate.SC_SERVER_ERRORS)
            .sendAwait()

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
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        client.get(port, "localhost", "/logs/processchains/$id")
            .`as`(BodyCodec.string())
            .putHeader("Range", "bytes=3-2")
            .expect(ResponsePredicate.SC_REQUESTED_RANGE_NOT_SATISFIABLE)
            .sendAwait()

        assertThat(agent1Asked.get()).isEqualTo(1)
        assertThat(agent2Asked.get()).isEqualTo(0)
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

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val client = WebClient.create(vertx)
        val response = client.get(port, "localhost", "/agents")
          .`as`(BodyCodec.jsonArray())
          .expect(ResponsePredicate.SC_OK)
          .expect(ResponsePredicate.JSON)
          .sendAwait()
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

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val client = WebClient.create(vertx)
        client.get(port, "localhost", "/agents")
          .expect(ResponsePredicate.SC_SERVICE_UNAVAILABLE)
          .sendAwait()
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

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val systemHealthy = services && agents && submissions && vms
        val client = WebClient.create(vertx)
        val response = client.get(port, "localhost", "/health")
          .`as`(BodyCodec.jsonObject())
          .expect(if (systemHealthy) ResponsePredicate.SC_OK else ResponsePredicate.SC_SERVICE_UNAVAILABLE)
          .expect(ResponsePredicate.JSON)
          .sendAwait()
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
}
