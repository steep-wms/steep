import AddressConstants.LOCAL_AGENT_ADDRESS_PREFIX
import TestMetadata.services
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.convertValue
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
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.impl.ConcurrentHashSet
import io.vertx.core.impl.NoStackTraceThrowable
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.deploymentOptionsOf
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.awaitResult
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import model.Submission
import model.Submission.Status
import model.processchain.Argument
import model.processchain.ProcessChain
import model.workflow.Action
import model.workflow.Variable
import model.workflow.Workflow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
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

    private lateinit var metadataRegistry: MetadataRegistry
    private lateinit var submissionRegistry: SubmissionRegistry

    /**
     * Create mocks for registries only once for the whole test class. This
     * speeds up test execution. Mocks must be cleared after each test in the
     * [tearDown] method!
     */
    @BeforeAll
    @JvmStatic
    fun setUpAll() {
      // mock metadata registry
      metadataRegistry = mockk()
      mockkObject(MetadataRegistryFactory)
      every { MetadataRegistryFactory.create(any()) } returns metadataRegistry

      // mock submission registry
      submissionRegistry = mockk()
      mockkObject(SubmissionRegistryFactory)
      every { SubmissionRegistryFactory.create(any()) } returns submissionRegistry
    }

    /**
     * Remove mocks after all tests have been executed
     */
    @AfterAll
    @JvmStatic
    fun tearDownAll() {
      unmockkAll()
    }
  }

  @BeforeEach
  fun setUp(vertx: Vertx, ctx: VertxTestContext, info: TestInfo) {
    // mock methods needed for every test
    coEvery { submissionRegistry.findSubmissionIdsByStatus(Status.RUNNING) } returns emptyList()
    coEvery { submissionRegistry.close() } just Runs

    CoroutineScope(vertx.dispatcher()).launch {
      // initialize plugin registry if necessary
      if (info.tags.contains(WITH_ADAPTER)) {
        val pluginRegistryConfig = jsonObjectOf(
            ConfigConstants.PLUGINS to "src/**/db/addRequiredCapabilityProcessChainAdapter.yaml"
        )
        PluginRegistryFactory.initialize(vertx, pluginRegistryConfig)
      }

      // deploy verticle under test
      val config = jsonObjectOf(
          ConfigConstants.TMP_PATH to "/tmp",
          ConfigConstants.OUT_PATH to "/out",
          ConfigConstants.CONTROLLER_LOOKUP_MAXERRORS to 2L,
          ConfigConstants.CONTROLLER_LOOKUP_INTERVAL to "0s"
      )
      val options = deploymentOptionsOf(config = config)
      vertx.deployVerticle(Controller(true), options, ctx.succeedingThenComplete())
    }
  }

  @AfterEach
  fun tearDown(vertx: Vertx, ctx: VertxTestContext) {
    CoroutineScope(vertx.dispatcher()).launch {
      // stop verticle before clearing mocks so any mocked cleanup method can
      // still be called
      vertx.deploymentIDs().forEach { deploymentId ->
        awaitResult<Void> { vertx.undeploy(deploymentId, it) }
      }

      // clear mocks after each test to reset state
      clearMocks(
          metadataRegistry,
          submissionRegistry
      )

      ctx.completeNow()
    }
  }

  private fun readWorkflow(name: String): Workflow {
    val fixture = javaClass.getResource("fixtures/$name.yaml")!!.readText()
    return YamlUtils.readValue(fixture)
  }

  private fun doSimple(vertx: Vertx, ctx: VertxTestContext,
      workflowName: String = "singleService",
      processChainStatusSupplier: suspend (processChain: ProcessChain, submissionId: String) -> ProcessChainStatus = { _, _ ->
        ProcessChainStatus.SUCCESS
      },
      processChainResultsSupplier: (processChain: ProcessChain) -> Map<String, List<String>> = { processChain ->
        processChain.executables.flatMap { it.arguments }.filter {
          it.type == Argument.Type.OUTPUT }.associate { (it.variable.id to listOf(it.variable.value)) }
      },
      processChainVerifier: ((processChain: ProcessChain) -> Unit)? = null,
      expectedSubmissionStatus: Status = Status.SUCCESS,
      completer: (ctx: VertxTestContext) -> Unit = { ctx.completeNow() },
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
        processChainVerifier?.invoke(processChain)
      }

      val processChainsById = processChainsSlot.captured.associateBy { it.id }
      val processChainIdsSlot = slot<Collection<String>>()
      coEvery { submissionRegistry.getProcessChainStatusAndResultsIfFinished(capture(processChainIdsSlot)) } coAnswers {
        processChainIdsSlot.captured.associateWith { id ->
          val pc = processChainsById[id]!!
          processChainStatusSupplier(pc, submission.id) to processChainResultsSupplier(pc)
        }
      }
    }

    coEvery { submissionRegistry.setSubmissionEndTime(submission.id, any()) } answers {
      ctx.verify {
        // verify that the submission was set to `expectedSubmissionStatus`
        coVerify(exactly = 1) {
          submissionRegistry.setSubmissionStatus(submission.id, expectedSubmissionStatus)
          submissionRegistry.setSubmissionStartTime(submission.id, any())
          submissionRegistry.setSubmissionEndTime(submission.id, any())
          verify?.let { it(submission) }
        }
      }
      completer(ctx)
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
    doSimple(vertx, ctx, processChainVerifier = { pc ->
      ctx.verify {
        assertThat(pc.requiredCapabilities).contains(NEW_REQUIRED_CAPABILITY)
      }
    })
  }

  /**
   * Runs a simple workflow that should fail
   * @param vertx the Vert.x instance
   * @param ctx the test context
   */
  @Test
  fun fail(vertx: Vertx, ctx: VertxTestContext) {
    coEvery { submissionRegistry.setSubmissionErrorMessage(any(), "All process chains failed") } just Runs
    doSimple(vertx, ctx, processChainStatusSupplier = { _, _ -> ProcessChainStatus.ERROR },
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
        processChainStatusSupplier = { _, _ ->
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
        processChainStatusSupplier = { _, _ ->
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
        processChainStatusSupplier = { _, _ ->
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

    val processChains = listOf(ProcessChain(), ProcessChain(), ProcessChain(),
        ProcessChain())

    @JsonIgnoreProperties(ignoreUnknown = true)
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

    // pretend that the first process chain has succeeded, the second and
    // third were still running and paused respectively, and that the fourth
    // one failed
    coEvery { submissionRegistry.countProcessChains(submission.id,
        ProcessChainStatus.RUNNING) } returns 1
    coEvery { submissionRegistry.countProcessChains(submission.id,
        ProcessChainStatus.PAUSED) } returns 1
    coEvery { submissionRegistry.countProcessChains(submission.id,
        ProcessChainStatus.ERROR) } returns 1
    coEvery { submissionRegistry.findProcessChainIdsBySubmissionIdAndStatus(submission.id,
        ProcessChainStatus.ERROR) } returns listOf(processChains[3].id)

    // make sure the controller resets the status of the failed process chain
    coEvery { submissionRegistry.setProcessChainStatus(processChains[3].id,
        ProcessChainStatus.REGISTERED) } just Runs

    // pretend the resumed process chains succeed
    var nProcessChainStatusAndResults = 0
    val processChainStatusAndResults = mutableMapOf(
        processChains[0].id to (ProcessChainStatus.SUCCESS to mapOf("output_file1" to listOf("/tmp/1"))),
        processChains[1].id to (ProcessChainStatus.RUNNING to null),
        processChains[2].id to (ProcessChainStatus.RUNNING to null),
        processChains[3].id to (ProcessChainStatus.RUNNING to null)
    )
    val processChainIdsSlot = slot<Collection<String>>()
    coEvery { submissionRegistry.getProcessChainStatusAndResultsIfFinished(capture(processChainIdsSlot)) } answers {
      val r = processChainIdsSlot.captured.associateWith { id ->
        processChainStatusAndResults[id] ?: throw IllegalStateException("Unknown process chain id: $id")
      }
      if (nProcessChainStatusAndResults == 0) {
        processChainStatusAndResults[processChains[1].id] =
            ProcessChainStatus.SUCCESS to mapOf("output_file2" to listOf("/tmp/2"))
        processChainStatusAndResults[processChains[2].id] =
            ProcessChainStatus.SUCCESS to mapOf("output_file3" to listOf("/tmp/3"))
        processChainStatusAndResults[processChains[3].id] =
            ProcessChainStatus.SUCCESS to mapOf("output_file4" to listOf("/tmp/4"))
      }
      nProcessChainStatusAndResults++
      r
    }

    // accept process chain start and end times
    coEvery { submissionRegistry.deleteAllProcessChainRuns(processChains[0].id) } just Runs
    coEvery { submissionRegistry.deleteAllProcessChainRuns(processChains[1].id) } just Runs
    coEvery { submissionRegistry.deleteAllProcessChainRuns(processChains[2].id) } just Runs
    coEvery { submissionRegistry.deleteAllProcessChainRuns(processChains[3].id) } just Runs

    // accept the new process chain
    val processChainsSlot = slot<List<ProcessChain>>()
    coEvery { submissionRegistry.addProcessChains(capture(processChainsSlot), submission.id) } answers {
      ctx.verify {
        assertThat(processChainsSlot.captured).hasSize(1)
        assertThat(processChainsSlot.captured[0].executables[0].id).isEqualTo("join")
      }
      for (processChain in processChainsSlot.captured) {
        processChainStatusAndResults[processChain.id] =
            ProcessChainStatus.SUCCESS to mapOf("output_file5" to listOf("/tmp/5"))
        coEvery { submissionRegistry.deleteAllProcessChainRuns(processChain.id) } just Runs
      }
    }

    // make sure the controller sets a new execution state
    val executionStateSlot = slot<JsonObject>()
    coEvery { submissionRegistry.setSubmissionExecutionState(submission.id,
        capture(executionStateSlot)) } answers {
      ctx.verify {
        val state = JsonUtils.mapper.convertValue<SmallState>(executionStateSlot.captured)
        assertThat(state.actions).hasSize(1)
        assertThat(state.actions.first()).isEqualTo(workflow.actions[3])
      }
    } andThenAnswer {
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
        processChainStatusSupplier = { processChain, _ ->
          if (processChain.executables.any { it.path == "join.sh" })
            ProcessChainStatus.CANCELLED else ProcessChainStatus.SUCCESS
        },
        expectedSubmissionStatus = Status.CANCELLED)
  }

  private fun doCancelRunning(initialStatus: ProcessChainStatus,
      vertx: Vertx, ctx: VertxTestContext) {
    var n = 0
    doSimple(vertx, ctx, workflowName = "smallGraph",
        processChainStatusSupplier = { processChain, _ ->
          if (processChain.executables.any { it.path == "join.sh" }) {
            n++
            if (n == 1) {
              initialStatus
            } else {
              ProcessChainStatus.CANCELLED
            }
          } else {
            ProcessChainStatus.SUCCESS
          }
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
    doCancelRunning(ProcessChainStatus.RUNNING, vertx, ctx)
  }

  /**
   * Tries to cancel a paused process chain
   * @param vertx the Vert.x instance
   * @param ctx the test context
   */
  @Test
  fun cancelPaused(vertx: Vertx, ctx: VertxTestContext) {
    doCancelRunning(ProcessChainStatus.PAUSED, vertx, ctx)
  }

  /**
   * Checks if a temporary database connection error can be ignored
   * @param vertx the Vert.x instance
   * @param ctx the test context
   */
  @Test
  fun ignoreDatabaseConnectionError(vertx: Vertx, ctx: VertxTestContext) {
    var statusRequested = 0
    doSimple(vertx, ctx,
        processChainStatusSupplier = { _, _ ->
          statusRequested++
          when (statusRequested) {
            1 -> ProcessChainStatus.RUNNING
            2 -> throw IllegalStateException("Temporary fault")
            else -> ProcessChainStatus.SUCCESS
          }
        },
        expectedSubmissionStatus = Status.SUCCESS) {
      assertThat(statusRequested).isEqualTo(3)
    }
  }

  /**
   * Checks if a permanent database connection error leads to a submission error
   * @param vertx the Vert.x instance
   * @param ctx the test context
   */
  @Test
  fun failOnDatabaseConnectionError(vertx: Vertx, ctx: VertxTestContext) {
    val expectedMessage = "Permanent fault"

    val errorMessageSubmissionIdSlot = slot<String>()
    val errorMessageMessageSlot = slot<String>()
    coEvery { submissionRegistry.setSubmissionErrorMessage(
      capture(errorMessageSubmissionIdSlot), capture(errorMessageMessageSlot)) } just Runs
    coEvery { submissionRegistry.setAllProcessChainsStatus(any(), any(), any()) } just Runs

    val processChainIds = ConcurrentHashSet<String>()
    coEvery { submissionRegistry.findProcessChainIdsBySubmissionIdAndStatus(any(), any()) } returns processChainIds

    var statusRequested = 0
    var consumerRegistered = false
    val processChainCancelled = Channel<Boolean>()
    doSimple(vertx, ctx,
      processChainStatusSupplier = { processChain, _ ->
        processChainIds.add(processChain.id)

        if (!consumerRegistered) {
          val address = LOCAL_AGENT_ADDRESS_PREFIX + processChain.id
          vertx.eventBus().consumer<JsonObject>(address).handler { msg ->
            if (msg.body().getString("action") == "cancel") {
              CoroutineScope(vertx.dispatcher()).launch {
                processChainCancelled.send(true)
              }
            }
          }
          consumerRegistered = true
        }

        statusRequested++
        when (statusRequested) {
          1 -> ProcessChainStatus.RUNNING
          else -> throw IllegalStateException(expectedMessage)
        }
      },
      expectedSubmissionStatus = Status.ERROR,
      completer = {
        CoroutineScope(vertx.dispatcher()).launch {
          ctx.coVerify {
            // check that the process chain has been cancelled
            assertThat(processChainCancelled.receive()).isTrue
          }
          ctx.completeNow()
        }
      }) { submission ->
      // submission should fail after two faults (see ConfigConstants.CONTROLLER_LOOKUP_MAX_ERRORS
      // set in this test's setUp method above)
      assertThat(statusRequested).isEqualTo(3)

      // check that the error message was correctly set
      assertThat(errorMessageSubmissionIdSlot.captured).isEqualTo(submission.id)
      assertThat(errorMessageMessageSlot.captured).isEqualTo(expectedMessage)
    }
  }

  /**
   * Runs a simple workflow and change the default priority in between
   * @param vertx the Vert.x instance
   * @param ctx the test context
   */
  @Test
  fun priorityChange(vertx: Vertx, ctx: VertxTestContext) {
    var priorityChanged = false
    val expectedPriority = 100
    var originalPriorityValidated = false
    var changedPriorityValidated = false
    doSimple(vertx, ctx, workflowName = "join",
        processChainStatusSupplier = { _, submissionId ->
          if (!priorityChanged) {
            vertx.eventBus().publish(AddressConstants.SUBMISSION_PRIORITY_CHANGED, jsonObjectOf(
                "submissionId" to submissionId,
                "priority" to expectedPriority
            ))

            // wait for the next event loop tick (i.e. make sure the message is processed)
            val p = Promise.promise<Unit>()
            vertx.runOnContext { p.complete() }
            p.future().await()

            priorityChanged = true
          }
          ProcessChainStatus.SUCCESS
        },
        processChainVerifier = { pc ->
          ctx.verify {
            if (!priorityChanged) {
              assertThat(pc.priority).isEqualTo(0)
              originalPriorityValidated = true
            } else {
              assertThat(pc.priority).isEqualTo(expectedPriority)
              changedPriorityValidated = true
            }
          }
        }) {
      assertThat(priorityChanged).isTrue
      assertThat(originalPriorityValidated).isTrue
      assertThat(changedPriorityValidated).isTrue()
    }
  }
}
