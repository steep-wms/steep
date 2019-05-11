import db.SubmissionRegistry
import db.SubmissionRegistry.ProcessChainStatus
import db.SubmissionRegistryFactory
import helper.JsonUtils
import helper.UniqueID
import helper.YamlUtils
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.predicate.ResponsePredicate
import io.vertx.ext.web.codec.BodyCodec
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.DeploymentOptions
import io.vertx.kotlin.core.json.JsonObject
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.kotlin.ext.web.client.sendAwait
import io.vertx.kotlin.ext.web.client.sendBufferAwait
import io.vertx.kotlin.ext.web.client.sendJsonObjectAwait
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import model.Submission
import model.processchain.Executable
import model.processchain.ProcessChain
import model.workflow.Workflow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.ServerSocket
import java.time.Instant

/**
 * Tests for [HttpEndpoint]
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class HttpEndpointTest {
  private val maxPostSize = 1024
  private var port: Int = 0
  private lateinit var submissionRegistry: SubmissionRegistry

  @BeforeEach
  fun setUp(vertx: Vertx, ctx: VertxTestContext) {
    port = ServerSocket(0).use { it.localPort }

    // mock submission registry
    submissionRegistry = mockk()
    mockkObject(SubmissionRegistryFactory)
    every { SubmissionRegistryFactory.create(any()) } returns submissionRegistry
    coEvery { submissionRegistry.close() } just Runs

    // deploy verticle under test
    val config = json {
      obj(
          ConfigConstants.HTTP_HOST to "localhost",
          ConfigConstants.HTTP_PORT to port,
          ConfigConstants.HTTP_POST_MAX_SIZE to maxPostSize
      )
    }
    val options = DeploymentOptions(config)
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
            .expect(ResponsePredicate.SC_SUCCESS)
            .expect(ResponsePredicate.JSON)
            .sendAwait()
        assertThat(response.body().map).containsKey("version")
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that the endpoint returns a list of workflows
   */
  @Test
  fun getWorkflows(vertx: Vertx, ctx: VertxTestContext) {
    val s1 = Submission(workflow = Workflow())
    coEvery { submissionRegistry.countProcessChainsBySubmissionId(s1.id) } returns 10
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        ProcessChainStatus.REGISTERED) } returns 1
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        ProcessChainStatus.RUNNING) } returns 2
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        ProcessChainStatus.ERROR) } returns 3
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        ProcessChainStatus.SUCCESS) } returns 4

    val s2 = Submission(workflow = Workflow())
    coEvery { submissionRegistry.countProcessChainsBySubmissionId(s2.id) } returns 50
    coEvery { submissionRegistry.countProcessChainsByStatus(s2.id,
        ProcessChainStatus.REGISTERED) } returns 11
    coEvery { submissionRegistry.countProcessChainsByStatus(s2.id,
        ProcessChainStatus.RUNNING) } returns 12
    coEvery { submissionRegistry.countProcessChainsByStatus(s2.id,
        ProcessChainStatus.ERROR) } returns 13
    coEvery { submissionRegistry.countProcessChainsByStatus(s2.id,
        ProcessChainStatus.SUCCESS) } returns 14

    coEvery { submissionRegistry.findSubmissions(any(), any(), any()) } returns listOf(s1, s2)
    coEvery { submissionRegistry.countSubmissions() } returns 2

    val client = WebClient.create(vertx)
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/workflows")
            .`as`(BodyCodec.jsonArray())
            .expect(ResponsePredicate.SC_SUCCESS)
            .expect(ResponsePredicate.JSON)
            .sendAwait()

        assertThat(response.headers()["x-page-size"]).isEqualTo("-1")
        assertThat(response.headers()["x-page-offset"]).isEqualTo("0")
        assertThat(response.headers()["x-page-total"]).isEqualTo("2")

        assertThat(response.body()).isEqualTo(json {
          array(
              obj(
                  "id" to s1.id,
                  "status" to Submission.Status.ACCEPTED.toString(),
                  "runningProcessChains" to 2,
                  "failedProcessChains" to 3,
                  "succeededProcessChains" to 4,
                  "totalProcessChains" to 10
              ),
              obj(
                  "id" to s2.id,
                  "status" to Submission.Status.ACCEPTED.toString(),
                  "runningProcessChains" to 12,
                  "failedProcessChains" to 13,
                  "succeededProcessChains" to 14,
                  "totalProcessChains" to 50
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
    coEvery { submissionRegistry.countProcessChainsBySubmissionId(s1.id) } returns 10
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        ProcessChainStatus.REGISTERED) } returns 1
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        ProcessChainStatus.RUNNING) } returns 2
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        ProcessChainStatus.ERROR) } returns 3
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        ProcessChainStatus.SUCCESS) } returns 4

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
            .expect(ResponsePredicate.SC_SUCCESS)
            .expect(ResponsePredicate.JSON)
            .sendAwait()

        assertThat(response.body()).isEqualTo(json {
            obj(
                "id" to s1.id,
                "workflow" to JsonUtils.toJson(s1.workflow),
                "status" to Submission.Status.ACCEPTED.toString(),
                "runningProcessChains" to 2,
                "failedProcessChains" to 3,
                "succeededProcessChains" to 4,
                "totalProcessChains" to 10
            )
        })
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

  private fun doPostWorkflow(vertx: Vertx, ctx: VertxTestContext, json: Boolean) {
    val w = Workflow()

    val submissionSlot = slot<Submission>()
    coEvery { submissionRegistry.addSubmission(capture(submissionSlot)) } answers {
      ctx.verify {
        assertThat(submissionSlot.captured.id).isNotNull()
        assertThat(submissionSlot.captured.status).isEqualTo(Submission.Status.ACCEPTED)
        assertThat(submissionSlot.captured.workflow).isEqualTo(w)
      }
    }

    val client = WebClient.create(vertx)
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val response = client.post(port, "localhost", "/workflows")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_ACCEPTED)
            .expect(ResponsePredicate.JSON)
            .let {
              if (json) {
                it.sendJsonObjectAwait(JsonUtils.toJson(w))
              } else {
                it.sendBufferAwait(Buffer.buffer(YamlUtils.mapper.writeValueAsString(w)))
              }
            }

        assertThat(response.body()).isEqualTo(json {
          obj(
              "id" to submissionSlot.captured.id,
              "workflow" to JsonUtils.toJson(w),
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
    doPostWorkflow(vertx, ctx, true)
  }

  /**
   * Test that a workflow can be successfully posted as YAML
   */
  @Test
  fun postWorkflowYaml(vertx: Vertx, ctx: VertxTestContext) {
    doPostWorkflow(vertx, ctx, false)
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

    coEvery { submissionRegistry.findProcessChains(any(), any(), any()) } returns listOf(
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
            .expect(ResponsePredicate.SC_SUCCESS)
            .expect(ResponsePredicate.JSON)
            .sendAwait()

        assertThat(response.headers()["x-page-size"]).isEqualTo("-1")
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
                  "endTime" to endTime,
                  "results" to obj(
                      "output_file1" to array("output.txt")
                  )
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

    coEvery { submissionRegistry.findProcessChainsBySubmissionId(s1.id, any(), any(), any()) } returns listOf(pc1, pc2)
    coEvery { submissionRegistry.countProcessChainsBySubmissionId(s1.id) } returns 2

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
            .expect(ResponsePredicate.SC_SUCCESS)
            .expect(ResponsePredicate.JSON)
            .sendAwait()

        assertThat(response.headers()["x-page-size"]).isEqualTo("-1")
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
                  "endTime" to endTime,
                  "results" to obj(
                      "output_file1" to array("output.txt")
                  )
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
            .expect(ResponsePredicate.SC_SUCCESS)
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
}
