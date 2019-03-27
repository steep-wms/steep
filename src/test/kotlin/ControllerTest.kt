import TestMetadata.services
import com.fasterxml.jackson.module.kotlin.readValue
import db.MetadataRegistry
import db.MetadataRegistryFactory
import db.SubmissionRegistry
import db.SubmissionRegistry.ProcessChainStatus
import db.SubmissionRegistryFactory
import helper.JsonUtils
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
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.DeploymentOptions
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import model.Submission
import model.Submission.Status
import model.processchain.ProcessChain
import model.workflow.Action
import model.workflow.Variable
import model.workflow.Workflow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Tests for the [Controller]
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class ControllerTest {
  private lateinit var metadataRegistry: MetadataRegistry
  private lateinit var submissionRegistry: SubmissionRegistry

  @BeforeEach
  fun setUp(vertx: Vertx, ctx: VertxTestContext) {
    // mock metadata registry
    metadataRegistry = mockk()
    mockkObject(MetadataRegistryFactory)
    every { MetadataRegistryFactory.create(any()) } returns metadataRegistry

    // mock submission registry
    submissionRegistry = mockk()
    mockkObject(SubmissionRegistryFactory)
    every { SubmissionRegistryFactory.create(any()) } returns submissionRegistry
    coEvery { submissionRegistry.findSubmissionIdsByStatus(Status.RUNNING) } returns emptyList()

    // deploy verticle under test
    val config = json {
      obj(
          ConfigConstants.TMP_PATH to "/tmp"
      )
    }
    val options = DeploymentOptions(config)
    vertx.deployVerticle(Controller::class.qualifiedName, options, ctx.completing())
  }

  @AfterEach
  fun tearDown() {
    unmockkAll()
  }

  private fun readWorkflow(name: String): Workflow {
    val fixture = javaClass.getResource("fixtures/$name.json").readText()
    return JsonUtils.mapper.readValue(fixture)
  }

  /**
   * Runs a simple test: schedules a workflow and waits until the controller
   * has executed it
   * @param vertx the Vert.x instance
   * @param ctx the test context
   */
  @Test
  fun simple(vertx: Vertx, ctx: VertxTestContext) {
    val workflow = readWorkflow("singleService")
    val submission = Submission(workflow = workflow)
    val acceptedSubmissions = mutableListOf(submission)

    // mock metadata registry
    coEvery { metadataRegistry.findServices() } returns services

    // mock submission registry
    coEvery { submissionRegistry.setSubmissionStatus(submission.id, Status.RUNNING) } just Runs
    coEvery { submissionRegistry.setSubmissionStatus(submission.id, Status.SUCCESS) } just Runs
    coEvery { submissionRegistry.getSubmissionStatus(submission.id) } returns Status.RUNNING
    coEvery { submissionRegistry.setSubmissionExecutionState(submission.id, any()) } just Runs
    coEvery { submissionRegistry.getSubmissionExecutionState(submission.id) } returns null
    coEvery { submissionRegistry.setSubmissionStartTime(submission.id, any()) } just Runs
    coEvery { submissionRegistry.setSubmissionEndTime(submission.id, any()) } just Runs

    val processChainsSlot = slot<List<ProcessChain>>()
    coEvery { submissionRegistry.addProcessChains(capture(processChainsSlot), submission.id) } answers {
      for (processChain in processChainsSlot.captured) {
        coEvery { submissionRegistry.getProcessChainStatus(processChain.id) } returns
            ProcessChainStatus.SUCCESS
        coEvery { submissionRegistry.getProcessChainResults(processChain.id) } returns
            mapOf("output_file1" to listOf("/tmp/0"))
      }
    }

    coEvery { submissionRegistry.setSubmissionEndTime(submission.id, any()) } answers {
      ctx.verify {
        // verify that the submission was set to SUCCESS
        coVerify(exactly = 1) {
          submissionRegistry.setSubmissionStatus(submission.id, Status.SUCCESS)
          submissionRegistry.setSubmissionStartTime(submission.id, any())
          submissionRegistry.setSubmissionEndTime(submission.id, any())
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
        val forEachOutputsToBeCollected: Map<String, List<Variable>> = emptyMap())
    val executionState = SmallState(workflow.vars, listOf(workflow.actions[3]))

    // mock metadata registry
    coEvery { metadataRegistry.findServices() } returns services

    // mock submission registry
    coEvery { submissionRegistry.getSubmissionStatus(submission.id) } returns Status.RUNNING
    coEvery { submissionRegistry.findProcessChainsBySubmissionId(submission.id) } returns processChains
    coEvery { submissionRegistry.getSubmissionExecutionState(submission.id) } returns
        JsonUtils.toJson(executionState)

    // there are no accepted submissions
    coEvery { submissionRegistry.fetchNextSubmission(Status.ACCEPTED, Status.RUNNING) } returns null

    // pretend that the first process chain has succeeded, the second was still
    // running, and the third failed
    coEvery { submissionRegistry.countProcessChainsByStatus(submission.id,
        ProcessChainStatus.RUNNING) } returns 1
    coEvery { submissionRegistry.countProcessChainsByStatus(submission.id,
        ProcessChainStatus.ERROR) } returns 1
    coEvery { submissionRegistry.findProcessChainStatusesBySubmissionId(submission.id) } returns mapOf(
        processChains[0].id to ProcessChainStatus.SUCCESS,
        processChains[1].id to ProcessChainStatus.ERROR,
        processChains[2].id to ProcessChainStatus.RUNNING
    )

    // make sure the controller resets the status of the failed and the running process chain
    coEvery { submissionRegistry.setProcessChainStatus(processChains[1].id,
        ProcessChainStatus.REGISTERED) } just Runs
    coEvery { submissionRegistry.setProcessChainStatus(processChains[2].id,
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
}
