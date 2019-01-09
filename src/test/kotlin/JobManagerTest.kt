import agent.LocalAgent
import agent.RemoteAgentRegistry
import db.SubmissionRegistry
import db.SubmissionRegistryFactory
import helper.JsonUtils
import helper.Shell
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.vertx.core.Vertx
import io.vertx.core.impl.NoStackTraceThrowable
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
import io.vertx.kotlin.ext.web.client.sendJsonObjectAwait
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import model.Submission
import model.processchain.ProcessChain
import model.workflow.Workflow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.io.IOException
import java.net.ServerSocket
import java.rmi.RemoteException

/**
 * Tests for [JobManager]
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class JobManagerTest {
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

    // deploy verticle under test
    val config = json {
      obj(
          ConfigConstants.HTTP_HOST to "localhost",
          ConfigConstants.HTTP_PORT to port,
          ConfigConstants.HTTP_POST_MAX_SIZE to maxPostSize
      )
    }
    val options = DeploymentOptions(config)
    vertx.deployVerticle(JobManager::class.qualifiedName, options, ctx.completing())
  }

  @AfterEach
  fun tearDown() {
    unmockkAll()
  }

  /**
   * Execute an empty process chain
   */
  @Test
  fun executeEmptyProcessChain(vertx: Vertx, ctx: VertxTestContext) {
    val processChain = ProcessChain()
    val remoteAgentRegistry = RemoteAgentRegistry(vertx)

    GlobalScope.launch(vertx.dispatcher()) {
      val agent = remoteAgentRegistry.allocate(processChain)
      try {
        assertThat(agent).isNotNull
        requireNotNull(agent)

        val results = agent.execute(processChain)
        assertThat(results).isEmpty()

        ctx.completeNow()
      } catch (t: Throwable) {
        ctx.failNow(t)
      }
    }
  }

  /**
   * Execute a simple process chain
   */
  @Test
  fun executeProcessChain(vertx: Vertx, ctx: VertxTestContext) {
    val processChain = ProcessChain()
    val remoteAgentRegistry = RemoteAgentRegistry(vertx)
    val expectedResults = mapOf("output_files" to listOf("test1", "test2"))

    mockkConstructor(LocalAgent::class)
    coEvery { anyConstructed<LocalAgent>().execute(processChain) } returns expectedResults

    GlobalScope.launch(vertx.dispatcher()) {
      val agent = remoteAgentRegistry.allocate(processChain)
      try {
        assertThat(agent).isNotNull
        requireNotNull(agent)

        val results = agent.execute(processChain)
        assertThat(results).isEqualTo(expectedResults)

        ctx.completeNow()
      } catch (t: Throwable) {
        ctx.failNow(t)
      }
    }
  }

  /**
   * Test what happens if [LocalAgent] throws an exception
   */
  @Test
  fun errorInLocalAgent(vertx: Vertx, ctx: VertxTestContext) {
    val processChain = ProcessChain()
    val remoteAgentRegistry = RemoteAgentRegistry(vertx)
    val errorMessage = "File not found"

    mockkConstructor(LocalAgent::class)
    coEvery { anyConstructed<LocalAgent>().execute(processChain) } throws
        IOException(errorMessage)

    GlobalScope.launch(vertx.dispatcher()) {
      val agent = remoteAgentRegistry.allocate(processChain)
      ctx.verify {
        assertThat(agent).isNotNull
      }

      try {
        agent!!.execute(processChain)
        ctx.failNow(NoStackTraceThrowable("Agent should throw"))
      } catch (e: RemoteException) {
        ctx.verify {
          assertThat(e).hasMessage(errorMessage)
        }
        ctx.completeNow()
      } catch (t: Throwable) {
        ctx.failNow(t)
      }
    }
  }

  /**
   * Test what happens if [LocalAgent] throws a [Shell.ExecutionException]
   */
  @Test
  fun executionExceptionInLocalAgent(vertx: Vertx, ctx: VertxTestContext) {
    val processChain = ProcessChain()
    val remoteAgentRegistry = RemoteAgentRegistry(vertx)
    val errorMessage = "Could not generate file"
    val lastOutput = "This is the last output"
    val exitCode = 132

    mockkConstructor(LocalAgent::class)
    coEvery { anyConstructed<LocalAgent>().execute(processChain) } throws
        Shell.ExecutionException(errorMessage, lastOutput, exitCode)

    GlobalScope.launch(vertx.dispatcher()) {
      val agent = remoteAgentRegistry.allocate(processChain)
      ctx.verify {
        assertThat(agent).isNotNull
      }

      try {
        agent!!.execute(processChain)
        ctx.failNow(NoStackTraceThrowable("Agent should throw"))
      } catch (e: RemoteException) {
        ctx.verify {
          assertThat(e).hasMessage("$errorMessage\n\nExit code: $exitCode\n\n$lastOutput")
        }
        ctx.completeNow()
      } catch (t: Throwable) {
        ctx.failNow(t)
      }
    }
  }

  /**
   * Check if the main entry point returns version information
   */
  @Test
  fun getVersion(vertx: Vertx, ctx: VertxTestContext) {
    val client = WebClient.create(vertx)
    GlobalScope.launch(vertx.dispatcher()) launch@ {
      val response = try {
        client.get(port, "localhost", "/")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_SUCCESS)
            .expect(ResponsePredicate.JSON)
            .sendAwait()
      } catch (t: Throwable) {
        ctx.failNow(t)
        return@launch
      }
      ctx.verify {
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
        SubmissionRegistry.ProcessChainStatus.REGISTERED) } returns 1
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        SubmissionRegistry.ProcessChainStatus.RUNNING) } returns 2
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        SubmissionRegistry.ProcessChainStatus.ERROR) } returns 3
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        SubmissionRegistry.ProcessChainStatus.SUCCESS) } returns 4

    val s2 = Submission(workflow = Workflow())
    coEvery { submissionRegistry.countProcessChainsBySubmissionId(s2.id) } returns 50
    coEvery { submissionRegistry.countProcessChainsByStatus(s2.id,
        SubmissionRegistry.ProcessChainStatus.REGISTERED) } returns 11
    coEvery { submissionRegistry.countProcessChainsByStatus(s2.id,
        SubmissionRegistry.ProcessChainStatus.RUNNING) } returns 12
    coEvery { submissionRegistry.countProcessChainsByStatus(s2.id,
        SubmissionRegistry.ProcessChainStatus.ERROR) } returns 13
    coEvery { submissionRegistry.countProcessChainsByStatus(s2.id,
        SubmissionRegistry.ProcessChainStatus.SUCCESS) } returns 14

    coEvery { submissionRegistry.findSubmissions() } returns listOf(s1, s2)

    val client = WebClient.create(vertx)
    GlobalScope.launch(vertx.dispatcher()) launch@ {
      val response = try {
        client.get(port, "localhost", "/workflows")
            .`as`(BodyCodec.jsonArray())
            .expect(ResponsePredicate.SC_SUCCESS)
            .expect(ResponsePredicate.JSON)
            .sendAwait()
      } catch (t: Throwable) {
        ctx.failNow(t)
        return@launch
      }
      ctx.verify {
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
        SubmissionRegistry.ProcessChainStatus.REGISTERED) } returns 1
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        SubmissionRegistry.ProcessChainStatus.RUNNING) } returns 2
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        SubmissionRegistry.ProcessChainStatus.ERROR) } returns 3
    coEvery { submissionRegistry.countProcessChainsByStatus(s1.id,
        SubmissionRegistry.ProcessChainStatus.SUCCESS) } returns 4

    coEvery { submissionRegistry.findSubmissionById(s1.id) } returns s1
    coEvery { submissionRegistry.findSubmissionById(neq(s1.id)) } returns null

    val client = WebClient.create(vertx)
    GlobalScope.launch(vertx.dispatcher()) launch@ {
      try {
        client.get(port, "localhost", "/workflows/${s1.id}_doesnotexist")
            .`as`(BodyCodec.none())
            .expect(ResponsePredicate.SC_NOT_FOUND)
            .sendAwait()
      } catch (t: Throwable) {
        ctx.failNow(t)
        return@launch
      }

      val response = try {
        client.get(port, "localhost", "/workflows/${s1.id}")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_SUCCESS)
            .expect(ResponsePredicate.JSON)
            .sendAwait()
      } catch (t: Throwable) {
        ctx.failNow(t)
        return@launch
      }

      ctx.verify {
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
      try {
        client.post(port, "localhost", "/workflows")
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .sendAwait()
        ctx.completeNow()
      } catch (t: Throwable) {
        ctx.failNow(t)
      }
    }
  }

  /**
   * Test that the endpoint rejects an invalid workflow
   */
  @Test
  fun postWorkflowInvalid(vertx: Vertx, ctx: VertxTestContext) {
    val client = WebClient.create(vertx)
    GlobalScope.launch(vertx.dispatcher()) {
      try {
        client.post(port, "localhost", "/workflows")
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .sendJsonObjectAwait(JsonObject().put("invalid", true))
        ctx.completeNow()
      } catch (t: Throwable) {
        ctx.failNow(t)
      }
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
      try {
        client.post(port, "localhost", "/workflows")
            .expect(ResponsePredicate.SC_REQUEST_ENTITY_TOO_LARGE)
            .sendJsonObjectAwait(JsonUtils.toJson(w))
        ctx.completeNow()
      } catch (t: Throwable) {
        ctx.failNow(t)
      }
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
      try {
        client.post(port, "localhost", "/workflows")
            .expect(ResponsePredicate.SC_BAD_REQUEST)
            .sendJsonObjectAwait(JsonUtils.toJson(w))
        ctx.completeNow()
      } catch (t: Throwable) {
        ctx.failNow(t)
      }
    }
  }

  /**
   * Test that a workflow can be successfully posted
   */
  @Test
  fun postWorkflow(vertx: Vertx, ctx: VertxTestContext) {
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
    GlobalScope.launch(vertx.dispatcher()) launch@ {
      val response = try {
        client.post(port, "localhost", "/workflows")
            .`as`(BodyCodec.jsonObject())
            .expect(ResponsePredicate.SC_ACCEPTED)
            .expect(ResponsePredicate.JSON)
            .sendJsonObjectAwait(JsonUtils.toJson(w))
      } catch (t: Throwable) {
        ctx.failNow(t)
        return@launch
      }

      ctx.verify {
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
}
