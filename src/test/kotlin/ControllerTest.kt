import TestMetadata.services
import db.MetadataRegistry
import db.MetadataRegistryFactory
import db.PluginRegistryFactory
import db.SubmissionRegistry
import db.SubmissionRegistry.ProcessChainStatus
import db.SubmissionRegistryFactory
import helper.JsonUtils
import helper.YamlUtils
import io.mockk.MockKVerificationScope
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
import io.vertx.core.impl.NoStackTraceThrowable
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.deploymentOptionsOf
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import model.Submission
import model.Submission.Status
import model.processchain.Argument
import model.processchain.ProcessChain
import model.workflow.Action
import model.workflow.Variable
import model.workflow.Workflow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Tests for the [Controller]
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class ControllerTest {
  companion object {
    private const val WITH_ADAPTER = "withAdapter"
    private const val NEW_REQUIRED_CAPABILITY = "NewRequiredCapability"
  }

  private lateinit var metadataRegistry: MetadataRegistry
  private lateinit var submissionRegistry: SubmissionRegistry

  @BeforeEach
  fun setUp(vertx: Vertx, ctx: VertxTestContext, info: TestInfo) {
    // mock metadata registry
    metadataRegistry = mockk()
    mockkObject(MetadataRegistryFactory)
    every { MetadataRegistryFactory.create(any()) } returns metadataRegistry

    // mock submission registry
    submissionRegistry = mockk()
    mockkObject(SubmissionRegistryFactory)
    every { SubmissionRegistryFactory.create(any()) } returns submissionRegistry
    coEvery { submissionRegistry.findSubmissionIdsByStatus(Status.RUNNING) } returns emptyList()
    coEvery { submissionRegistry.close() } just Runs

    GlobalScope.launch(vertx.dispatcher()) {
      // initialize plugin registry if necessary
      if (info.tags.contains(WITH_ADAPTER)) {
        val pluginRegistryConfig = json {
          obj(
              ConfigConstants.PLUGINS to "src/**/db/addRequiredCapabilityProcessChainAdapter.yaml"
          )
        }
        PluginRegistryFactory.initialize(vertx, pluginRegistryConfig)
      }

      // deploy verticle under test
      val config = json {
        obj(
            ConfigConstants.TMP_PATH to "/tmp",
            ConfigConstants.OUT_PATH to "/out"
        )
      }
      val options = deploymentOptionsOf(config)
      vertx.deployVerticle(Controller::class.qualifiedName, options, ctx.completing())
    }
  }

  @AfterEach
  fun tearDown() {
    unmockkAll()
  }

  private fun readWorkflow(name: String): Workflow {
    val fixture = javaClass.getResource("fixtures/$name.yaml")!!.readText()
    return YamlUtils.readValue(fixture)
  }

  private fun doSimple(vertx: Vertx, ctx: VertxTestContext,
      withAdapter: Boolean = false, workflowName: String = "singleService",
      processChainStatusSupplier: (processChain: ProcessChain) -> ProcessChainStatus = {
        ProcessChainStatus.SUCCESS
      },
      processChainResultsSupplier: (processChain: ProcessChain) -> Map<String, List<String>> = { processChain ->
        processChain.executables.flatMap { it.arguments }.filter {
          it.type == Argument.Type.OUTPUT }.associate { (it.variable.id to listOf(it.variable.value)) }
      },
      expectedSubmissionStatus: Status = Status.SUCCESS,
      verify: (suspend MockKVerificationScope.(Submission) -> Unit)? = null) {
    val workflow = readWorkflow(workflowName)
    val submission = Submission(workflow = workflow)
    val acceptedSubmissions = mutableListOf(submission)

    // mock metadata registry
    coEvery { metadataRegistry.findServices() } returns services

    // mock submission registry
    coEvery { submissionRegistry.setSubmissionStatus(submission.id, Status.RUNNING) } just Runs
    coEvery { submissionRegistry.setSubmissionStatus(submission.id, expectedSubmissionStatus) } just Runs
    coEvery { submissionRegistry.getSubmissionStatus(submission.id) } returns Status.RUNNING
    coEvery { submissionRegistry.setSubmissionExecutionState(submission.id, any()) } just Runs
    coEvery { submissionRegistry.getSubmissionExecutionState(submission.id) } returns null
    coEvery { submissionRegistry.setSubmissionStartTime(submission.id, any()) } just Runs
    coEvery { submissionRegistry.setSubmissionEndTime(submission.id, any()) } just Runs

    val processChainsSlot = slot<List<ProcessChain>>()
    coEvery { submissionRegistry.addProcessChains(capture(processChainsSlot), submission.id) } answers {
      for (processChain in processChainsSlot.captured) {
        if (withAdapter) {
          ctx.verify {
            assertThat(processChain.requiredCapabilities).contains(NEW_REQUIRED_CAPABILITY)
          }
        }

        coEvery { submissionRegistry.getProcessChainStatus(processChain.id) } answers {
          processChainStatusSupplier(processChain)
        }

        val results = processChainResultsSupplier(processChain)
        coEvery { submissionRegistry.getProcessChainResults(processChain.id) } returns results

        coEvery { submissionRegistry.setProcessChainStartTime(processChain.id, any()) } just Runs
      }
    }

    coEvery { submissionRegistry.setSubmissionEndTime(submission.id, any()) } answers {
      ctx.verify {
        // verify that the submission was set to SUCCESS
        coVerify(exactly = 1) {
          submissionRegistry.setSubmissionStatus(submission.id, expectedSubmissionStatus)
          submissionRegistry.setSubmissionStartTime(submission.id, any())
          submissionRegistry.setSubmissionEndTime(submission.id, any())
          verify?.let { it(submission) }
        }
      }
      ctx.completeNow()
    }

    // execute submissions
    coEvery { submissionRegistry.fetchNextSubmission(Status.ACCEPTED, Status.RUNNING) } answers {
      if (acceptedSubmissions.isEmpty()) null else acceptedSubmissions.removeAt(0)
    }

    vertx.eventBus().publish(AddressConstants.CONTROLLER_LOOKUP_NOW, null)
  }

  /**
   * Runs a simple test: schedules a workflow and waits until the controller
   * has executed it
   * @param vertx the Vert.x instance
   * @param ctx the test context
   */
  @Test
  fun simple(vertx: Vertx, ctx: VertxTestContext) {
    doSimple(vertx, ctx)
  }

  /**
   * Similar to [simple] but modifies the process chains with a process chain
   * adapter plugin
   * @param vertx the Vert.x instance
   * @param ctx the test context
   */
  @Test
  @Tag(WITH_ADAPTER)
  fun simpleWithAdapter(vertx: Vertx, ctx: VertxTestContext) {
    doSimple(vertx, ctx, true)
  }

  /**
   * Runs a simple workflow that should fail
   * @param vertx the Vert.x instance
   * @param ctx the test context
   */
  @Test
  fun fail(vertx: Vertx, ctx: VertxTestContext) {
    coEvery { submissionRegistry.setSubmissionErrorMessage(any(), "All process chains failed") } just Runs
    doSimple(vertx, ctx, processChainStatusSupplier = { ProcessChainStatus.ERROR },
        expectedSubmissionStatus = Status.ERROR) { submission ->
      submissionRegistry.setSubmissionErrorMessage(submission.id, "All process chains failed")
    }
  }

  /**
   * Runs a simple workflow that should fails partially
   * @param vertx the Vert.x instance
   * @param ctx the test context
   */
  @Test
  fun partial(vertx: Vertx, ctx: VertxTestContext) {
    var n = 0
    doSimple(vertx, ctx, workflowName = "twoIndependentServices",
        processChainStatusSupplier = {
          n += 1
          if (n == 1) ProcessChainStatus.SUCCESS else ProcessChainStatus.ERROR
        },
        expectedSubmissionStatus = Status.PARTIAL_SUCCESS) {
      assertThat(n).isEqualTo(2)
    }
  }

  /**
   * Runs a simple workflow that does not finish completely because two
   * process chains failed
   * @param vertx the Vert.x instance
   * @param ctx the test context
   */
  @Test
  fun failTwoProcessChains(vertx: Vertx, ctx: VertxTestContext) {
    coEvery { submissionRegistry.setSubmissionErrorMessage(any(),
        "Submission was not executed completely because 2 process chains failed") } just Runs
    var n = 0
    doSimple(vertx, ctx, workflowName = "smallGraph",
        processChainStatusSupplier = {
          n += 1
          if (n == 1) ProcessChainStatus.SUCCESS else ProcessChainStatus.ERROR
        },
        expectedSubmissionStatus = Status.ERROR) {
      assertThat(n).isEqualTo(3)
    }
  }

  /**
   * Runs a simple workflow that does not finish completely because one
   * process chain failed
   * @param vertx the Vert.x instance
   * @param ctx the test context
   */
  @Test
  fun failOneProcessChain(vertx: Vertx, ctx: VertxTestContext) {
    coEvery { submissionRegistry.setSubmissionErrorMessage(any(),
        "Submission was not executed completely because a process chain failed") } just Runs
    var n = 0
    doSimple(vertx, ctx, workflowName = "join",
        processChainStatusSupplier = {
          n += 1
          if (n == 1) ProcessChainStatus.SUCCESS else ProcessChainStatus.ERROR
        },
        expectedSubmissionStatus = Status.ERROR) {
      assertThat(n).isEqualTo(2)
    }
  }

  /**
   * Runs a simple workflow that does not finish completely because a process
   * chain does not return results
   * @param vertx the Vert.x instance
   * @param ctx the test context
   */
  @Test
  fun notFinished(vertx: Vertx, ctx: VertxTestContext) {
    coEvery { submissionRegistry.setSubmissionErrorMessage(any(),
        "Submission was not executed completely. There is at least one " +
            "action in the workflow that has not been executed because " +
            "its input was not available.") } just Runs
    doSimple(vertx, ctx, workflowName = "join",
        processChainResultsSupplier = { emptyMap() },
        expectedSubmissionStatus = Status.ERROR)
  }

  /**
   * Executes a simple workflow and checks if the submission results have been
   * set correctly
   * @param vertx the Vert.x instance
   * @param ctx the test context
   */
  @Test
  fun submissionResults(vertx: Vertx, ctx: VertxTestContext) {
    val resultsSlot = slot<Map<String, List<Any>>>()
    coEvery { submissionRegistry.setSubmissionResults(any(), capture(resultsSlot)) } answers {
      ctx.verify {
        assertThat(resultsSlot.captured).hasSize(2)
        assertThat(resultsSlot.captured["output_file1"])
            .hasSize(1)
            .allMatch { it is String && it.startsWith("/out/") }
        assertThat(resultsSlot.captured["output_file3"])
            .hasSize(1)
            .allMatch { it is String && it.startsWith("/out/") }
      }
    }

    doSimple(vertx, ctx, workflowName = "storeThreeDependent") { submission ->
      submissionRegistry.setSubmissionResults(submission.id, resultsSlot.captured)
    }
  }

  /**
   * Tries to resume a workflow
   * @param vertx the Vert.x instance
   * @param ctx the test context
   */
  @Test
  fun resume(vertx: Vertx, ctx: VertxTestContext) {
    val workflow = readWorkflow("diamond")
    val submission = Submission(workflow = workflow)
    val runningSubmissions = mutableListOf(submission)

    val processChains = listOf(ProcessChain(), ProcessChain(), ProcessChain())

    data class SmallState(val vars: List<Variable>, val actions: List<Action>,
        val variableValues: Map<String, Any> = emptyMap(),
        val forEachOutputsToBeCollected: Map<String, List<Variable>> = emptyMap(),
        val iterations: Map<String, Int> = emptyMap())
    val executionState = SmallState(workflow.vars, listOf(workflow.actions[3]))

    // mock metadata registry
    coEvery { metadataRegistry.findServices() } returns services

    // mock submission registry
    coEvery { submissionRegistry.getSubmissionStatus(submission.id) } returns Status.RUNNING
    coEvery { submissionRegistry.findProcessChains(submission.id) } returns
        processChains.map { it to submission.id }
    coEvery { submissionRegistry.getSubmissionExecutionState(submission.id) } returns
        JsonUtils.toJson(executionState)

    // there are no accepted submissions
    coEvery { submissionRegistry.fetchNextSubmission(Status.ACCEPTED, Status.RUNNING) } returns null

    // pretend that the first process chain has succeeded, the second was still
    // running, and the third failed
    coEvery { submissionRegistry.countProcessChains(submission.id,
        ProcessChainStatus.RUNNING) } returns 1
    coEvery { submissionRegistry.countProcessChains(submission.id,
        ProcessChainStatus.ERROR) } returns 1
    coEvery { submissionRegistry.findProcessChainIdsBySubmissionIdAndStatus(submission.id,
        ProcessChainStatus.ERROR) } returns listOf(processChains[1].id)

    // make sure the controller resets the status of the failed process chain
    coEvery { submissionRegistry.setProcessChainStatus(processChains[1].id,
        ProcessChainStatus.REGISTERED) } just Runs
    coEvery { submissionRegistry.setProcessChainErrorMessage(processChains[1].id, null) } just Runs

    // pretend the resumed process chains succeed
    coEvery { submissionRegistry.getProcessChainStatus(processChains[0].id) } returns
        ProcessChainStatus.SUCCESS
    coEvery { submissionRegistry.getProcessChainStatus(processChains[1].id) } returns
        ProcessChainStatus.RUNNING andThen ProcessChainStatus.SUCCESS
    coEvery { submissionRegistry.getProcessChainStatus(processChains[2].id) } returns
        ProcessChainStatus.RUNNING andThen ProcessChainStatus.SUCCESS

    // return process chain results
    coEvery { submissionRegistry.getProcessChainResults(processChains[0].id) } returns
        mapOf("output_file1" to listOf("/tmp/1"))
    coEvery { submissionRegistry.getProcessChainResults(processChains[1].id) } returns
        mapOf("output_file2" to listOf("/tmp/2"))
    coEvery { submissionRegistry.getProcessChainResults(processChains[2].id) } returns
        mapOf("output_file3" to listOf("/tmp/3"))

    // accept process chain start and end times
    coEvery { submissionRegistry.setProcessChainStartTime(processChains[0].id, any()) } just Runs
    coEvery { submissionRegistry.setProcessChainStartTime(processChains[1].id, any()) } just Runs
    coEvery { submissionRegistry.setProcessChainStartTime(processChains[2].id, any()) } just Runs
    coEvery { submissionRegistry.setProcessChainEndTime(processChains[0].id, any()) } just Runs
    coEvery { submissionRegistry.setProcessChainEndTime(processChains[1].id, any()) } just Runs
    coEvery { submissionRegistry.setProcessChainEndTime(processChains[2].id, any()) } just Runs

    // accept the new process chain
    val processChainsSlot = slot<List<ProcessChain>>()
    coEvery { submissionRegistry.addProcessChains(capture(processChainsSlot), submission.id) } answers {
      ctx.verify {
        assertThat(processChainsSlot.captured).hasSize(1)
        assertThat(processChainsSlot.captured[0].executables[0].id).isEqualTo("join")
      }
      for (processChain in processChainsSlot.captured) {
        coEvery { submissionRegistry.getProcessChainStatus(processChain.id) } returns
            ProcessChainStatus.SUCCESS
        coEvery { submissionRegistry.getProcessChainResults(processChain.id) } returns
            mapOf("output_file4" to listOf("/tmp/4"))
        coEvery { submissionRegistry.setProcessChainStartTime(processChain.id, any()) } just Runs
        coEvery { submissionRegistry.setProcessChainEndTime(processChain.id, any()) } just Runs
      }
    }

    // make sure the controller sets a new execution state
    val executionStateSlot = slot<JsonObject>()
    coEvery { submissionRegistry.setSubmissionExecutionState(submission.id,
        capture(executionStateSlot)) } answers {
      ctx.verify {
        assertThat(executionStateSlot.captured.getJsonArray("actions")).isEqualTo(JsonArray())
      }
    }

    // finish submission
    coEvery { submissionRegistry.setSubmissionStatus(submission.id, Status.SUCCESS) } answers {
      runningSubmissions.clear()
    }
    coEvery { submissionRegistry.setSubmissionStatus(submission.id, Status.ERROR) } answers {
      ctx.failNow(NoStackTraceThrowable("Submission failed"))
    }
    coEvery { submissionRegistry.setSubmissionEndTime(submission.id, any()) } just Runs
    coEvery { submissionRegistry.setSubmissionExecutionState(submission.id, null) } answers {
      ctx.completeNow()
    }

    // return submission to resume
    coEvery { submissionRegistry.findSubmissionIdsByStatus(Status.RUNNING) } answers {
      if (runningSubmissions.isEmpty()) emptyList() else runningSubmissions.map { it.id }
    }
    coEvery { submissionRegistry.findSubmissionById(submission.id) } returns submission

    vertx.eventBus().publish(AddressConstants.CONTROLLER_LOOKUP_ORPHANS_NOW, null)
  }

  /**
   * Tries to cancel a workflow
   * @param vertx the Vert.x instance
   * @param ctx the test context
   */
  @Test
  fun cancel(vertx: Vertx, ctx: VertxTestContext) {
    doSimple(vertx, ctx, workflowName = "smallGraph",
        processChainStatusSupplier = { processChain ->
          if (processChain.executables.any { it.path == "join.sh" })
            ProcessChainStatus.CANCELLED else ProcessChainStatus.SUCCESS
        },
        expectedSubmissionStatus = Status.CANCELLED)
  }

  /**
   * Tries to cancel an already running process chain
   * @param vertx the Vert.x instance
   * @param ctx the test context
   */
  @Test
  fun cancelRunning(vertx: Vertx, ctx: VertxTestContext) {
    var n = 0
    doSimple(vertx, ctx, workflowName = "smallGraph",
        processChainStatusSupplier = { processChain ->
          if (processChain.executables.any { it.path == "join.sh" }) {
            n++
            if (n == 1) {
              ProcessChainStatus.RUNNING
            } else {
              ProcessChainStatus.CANCELLED
            }
          } else {
            ProcessChainStatus.SUCCESS
          }
        },
        expectedSubmissionStatus = Status.CANCELLED)
  }
}
