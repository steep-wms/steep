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
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.DeploymentOptions
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import model.Submission
import model.Submission.Status
import model.processchain.ProcessChain
import model.workflow.Workflow
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
}
