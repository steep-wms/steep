import com.fasterxml.jackson.module.kotlin.convertValue
import db.MetadataRegistry
import db.MetadataRegistryFactory
import db.SubmissionRegistry
import db.SubmissionRegistry.ProcessChainStatus
import db.SubmissionRegistryFactory
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
import model.metadata.Cardinality
import model.metadata.Service
import model.metadata.ServiceParameter
import model.processchain.Argument
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
  private lateinit var metadataRegistry: MetadataRegistry

  @BeforeEach
  fun setUp(vertx: Vertx, ctx: VertxTestContext) {
    port = ServerSocket(0).use { it.localPort }

    // mock submission registry
    submissionRegistry = mockk()
    mockkObject(SubmissionRegistryFactory)
    every { SubmissionRegistryFactory.create(any()) } returns submissionRegistry
    coEvery { submissionRegistry.close() } just Runs

    // mock metadata registry
    metadataRegistry = mockk()
    mockkObject(MetadataRegistryFactory)
    every { MetadataRegistryFactory.create(any()) } returns metadataRegistry

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
            .expect(ResponsePredicate.SC_SUCCESS)
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
            .expect(ResponsePredicate.SC_SUCCESS)
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
    coEvery { submissionRegistry.countProcessChainsBySubmissionId(s1.id) } returns 15
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        ProcessChainStatus.REGISTERED) } returns 1
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        ProcessChainStatus.RUNNING) } returns 2
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        ProcessChainStatus.CANCELLED) } returns 3
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        ProcessChainStatus.ERROR) } returns 4
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        ProcessChainStatus.SUCCESS) } returns 5

    val s2 = Submission(workflow = Workflow())
    coEvery { submissionRegistry.countProcessChainsBySubmissionId(s2.id) } returns 65
    coEvery { submissionRegistry.countProcessChainsByStatus(s2.id,
        ProcessChainStatus.REGISTERED) } returns 11
    coEvery { submissionRegistry.countProcessChainsByStatus(s2.id,
        ProcessChainStatus.RUNNING) } returns 12
    coEvery { submissionRegistry.countProcessChainsByStatus(s2.id,
        ProcessChainStatus.CANCELLED) } returns 13
    coEvery { submissionRegistry.countProcessChainsByStatus(s2.id,
        ProcessChainStatus.ERROR) } returns 14
    coEvery { submissionRegistry.countProcessChainsByStatus(s2.id,
        ProcessChainStatus.SUCCESS) } returns 15

    val s3 = Submission(workflow = Workflow(), status = Submission.Status.SUCCESS)
    coEvery { submissionRegistry.countProcessChainsBySubmissionId(s3.id) } returns 1
    coEvery { submissionRegistry.countProcessChainsByStatus(s3.id,
        ProcessChainStatus.REGISTERED) } returns 0
    coEvery { submissionRegistry.countProcessChainsByStatus(s3.id,
        ProcessChainStatus.RUNNING) } returns 0
    coEvery { submissionRegistry.countProcessChainsByStatus(s3.id,
        ProcessChainStatus.CANCELLED) } returns 0
    coEvery { submissionRegistry.countProcessChainsByStatus(s3.id,
        ProcessChainStatus.ERROR) } returns 0
    coEvery { submissionRegistry.countProcessChainsByStatus(s3.id,
        ProcessChainStatus.SUCCESS) } returns 1

    coEvery { submissionRegistry.findSubmissions(any(), any(), any()) } returns listOf(s1, s2, s3)
    coEvery { submissionRegistry.countSubmissions() } returns 3

    val client = WebClient.create(vertx)
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/workflows")
            .`as`(BodyCodec.jsonArray())
            .expect(ResponsePredicate.SC_SUCCESS)
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
                  "totalProcessChains" to 15
              ),
              obj(
                  "id" to s2.id,
                  "status" to Submission.Status.ACCEPTED.toString(),
                  "runningProcessChains" to 12,
                  "cancelledProcessChains" to 13,
                  "failedProcessChains" to 14,
                  "succeededProcessChains" to 15,
                  "totalProcessChains" to 65
              ),
              obj(
                  "id" to s3.id,
                  "status" to Submission.Status.SUCCESS.toString(),
                  "runningProcessChains" to 0,
                  "cancelledProcessChains" to 0,
                  "failedProcessChains" to 0,
                  "succeededProcessChains" to 1,
                  "totalProcessChains" to 1
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
    coEvery { submissionRegistry.countProcessChainsBySubmissionId(s1.id) } returns 15
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        ProcessChainStatus.REGISTERED) } returns 1
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        ProcessChainStatus.RUNNING) } returns 2
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        ProcessChainStatus.CANCELLED) } returns 3
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        ProcessChainStatus.ERROR) } returns 4
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        ProcessChainStatus.SUCCESS) } returns 5

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
                "cancelledProcessChains" to 3,
                "failedProcessChains" to 4,
                "succeededProcessChains" to 5,
                "totalProcessChains" to 15
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
    coEvery { submissionRegistry.countProcessChainsBySubmissionId(s1.id) } returns 1
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        ProcessChainStatus.REGISTERED) } returns 0
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        ProcessChainStatus.RUNNING) } returns 0
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        ProcessChainStatus.CANCELLED) } returns 0
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        ProcessChainStatus.ERROR) } returns 0
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        ProcessChainStatus.SUCCESS) } returns 1

    coEvery { submissionRegistry.findSubmissionById(s1.id) } returns s1

    val results = mapOf("output_file1" to listOf("/out/test.txt"))
    coEvery { submissionRegistry.getSubmissionResults(s1.id) } returns results

    val client = WebClient.create(vertx)
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/workflows/${s1.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_SUCCESS)
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
              "results" to JsonUtils.toJson(results)
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
    coEvery { submissionRegistry.countProcessChainsBySubmissionId(s1.id) } returns 1
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        ProcessChainStatus.REGISTERED) } returns 0
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        ProcessChainStatus.RUNNING) } returns 0
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        ProcessChainStatus.CANCELLED) } returns 0
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        ProcessChainStatus.ERROR) } returns 1
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        ProcessChainStatus.SUCCESS) } returns 0

    coEvery { submissionRegistry.findSubmissionById(s1.id) } returns s1

    val errorMessage = "This is an error message"
    coEvery { submissionRegistry.getSubmissionErrorMessage(s1.id) } returns errorMessage

    val client = WebClient.create(vertx)
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val response = client.get(port, "localhost", "/workflows/${s1.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_SUCCESS)
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
              "errorMessage" to errorMessage
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
        coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
            ProcessChainStatus.RUNNING) } returns 1
        coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
            ProcessChainStatus.CANCELLED) } returns 2
        coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
            ProcessChainStatus.SUCCESS) } returns 0
        coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
            ProcessChainStatus.ERROR) } returns 0
        coEvery { submissionRegistry.countProcessChainsBySubmissionId(s1.id) } returns 3
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
}
