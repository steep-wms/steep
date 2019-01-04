package db

import io.vertx.core.Vertx
import io.vertx.core.impl.NoStackTraceThrowable
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import model.Submission
import model.processchain.ProcessChain
import model.workflow.Workflow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Tests for all [SubmissionRegistry] implementations
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
abstract class SubmissionRegistryTest {
  abstract fun createRegistry(vertx: Vertx): SubmissionRegistry

  private lateinit var submissionRegistry: SubmissionRegistry

  @BeforeEach
  fun setUp(vertx: Vertx) {
    submissionRegistry = createRegistry(vertx)
  }

  @Test
  fun addSubmission(vertx: Vertx, ctx: VertxTestContext) {
    val s = Submission(workflow = Workflow())

    GlobalScope.launch(vertx.dispatcher()) {
      submissionRegistry.addSubmission(s)
      val s2 = submissionRegistry.findSubmissionById(s.id)

      ctx.verify {
        assertThat(s2).isEqualTo(s)
      }

      ctx.completeNow()
    }
  }

  @Test
  fun fetchNextSubmission(vertx: Vertx, ctx: VertxTestContext) {
    val s = Submission(workflow = Workflow())

    GlobalScope.launch(vertx.dispatcher()) {
      submissionRegistry.addSubmission(s)
      val s2 = submissionRegistry.fetchNextSubmission(Submission.Status.ACCEPTED,
          Submission.Status.RUNNING)
      val status = submissionRegistry.findSubmissionById(s.id)?.status
      val s3 = submissionRegistry.fetchNextSubmission(Submission.Status.ACCEPTED,
          Submission.Status.RUNNING)
      ctx.verify {
        assertThat(s2).isEqualTo(s)
        assertThat(s3).isNull()
        assertThat(status).isNotNull.isEqualTo(Submission.Status.RUNNING)
      }

      ctx.completeNow()
    }
  }

  @Test
  fun setSubmissionStatus(vertx: Vertx, ctx: VertxTestContext) {
    val s = Submission(workflow = Workflow())

    GlobalScope.launch(vertx.dispatcher()) {
      submissionRegistry.addSubmission(s)
      val submissions = submissionRegistry.findSubmissions()
      val acceptedSubmission1 = submissionRegistry.fetchNextSubmission(
          Submission.Status.ACCEPTED, Submission.Status.ACCEPTED)
      val runningSubmission1 = submissionRegistry.fetchNextSubmission(
          Submission.Status.RUNNING, Submission.Status.RUNNING)

      ctx.verify {
        assertThat(submissions)
            .hasSize(1)
            .contains(s)
        assertThat(acceptedSubmission1)
            .isEqualTo(s)
        assertThat(runningSubmission1)
            .isNull()
      }

      submissionRegistry.setSubmissionStatus(s.id, Submission.Status.RUNNING)

      val acceptedSubmission2 = submissionRegistry.fetchNextSubmission(
          Submission.Status.ACCEPTED, Submission.Status.ACCEPTED)
      val runningSubmission2 = submissionRegistry.fetchNextSubmission(
          Submission.Status.RUNNING, Submission.Status.RUNNING)

      ctx.verify {
        assertThat(acceptedSubmission2)
            .isNull()
        assertThat(runningSubmission2)
            .isEqualTo(s.copy(status = Submission.Status.RUNNING))
      }

      ctx.completeNow()
    }
  }

  @Test
  fun addProcessChain(vertx: Vertx, ctx: VertxTestContext) {
    val s = Submission(workflow = Workflow())
    val pc = ProcessChain()

    GlobalScope.launch(vertx.dispatcher()) {
      submissionRegistry.addSubmission(s)
      submissionRegistry.addProcessChains(listOf(pc), s.id)
      val pcs = submissionRegistry.findProcessChainsBySubmissionId(s.id)
      val registeredPc = submissionRegistry.fetchNextProcessChain(
          SubmissionRegistry.ProcessChainStatus.REGISTERED,
          SubmissionRegistry.ProcessChainStatus.REGISTERED)

      ctx.verify {
        assertThat(pcs)
            .hasSize(1)
            .contains(pc)
        assertThat(registeredPc)
            .isEqualTo(pc)
      }

      ctx.completeNow()
    }
  }

  @Test
  fun fetchNextProcessChain(vertx: Vertx, ctx: VertxTestContext) {
    val s = Submission(workflow = Workflow())
    val pc = ProcessChain()

    GlobalScope.launch(vertx.dispatcher()) {
      submissionRegistry.addSubmission(s)
      submissionRegistry.addProcessChains(listOf(pc), s.id)
      val pc2 = submissionRegistry.fetchNextProcessChain(
          SubmissionRegistry.ProcessChainStatus.REGISTERED,
          SubmissionRegistry.ProcessChainStatus.RUNNING)
      val status = submissionRegistry.getProcessChainStatus(pc.id)
      val pc3 = submissionRegistry.fetchNextProcessChain(
          SubmissionRegistry.ProcessChainStatus.REGISTERED,
          SubmissionRegistry.ProcessChainStatus.RUNNING)
      ctx.verify {
        assertThat(pc2).isEqualTo(pc)
        assertThat(pc3).isNull()
        assertThat(status).isEqualTo(SubmissionRegistry.ProcessChainStatus.RUNNING)
      }

      ctx.completeNow()
    }
  }

  @Test
  fun addProcessChainToMissingSubmission(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      try {
        submissionRegistry.addProcessChains(listOf(ProcessChain()), "MISSING")
        throw NoStackTraceThrowable("addProcessChain should throw")
      } catch (e: NoSuchElementException) {
        ctx.completeNow()
      } catch (e: Throwable) {
        ctx.failNow(e)
      }
    }
  }

  @Test
  fun setProcessChainStatus(vertx: Vertx, ctx: VertxTestContext) {
    val s = Submission(workflow = Workflow())
    val pc = ProcessChain()

    GlobalScope.launch(vertx.dispatcher()) {
      submissionRegistry.addSubmission(s)
      submissionRegistry.addProcessChains(listOf(pc), s.id)
      val pcs = submissionRegistry.findProcessChainsBySubmissionId(s.id)
      val registeredPc1 = submissionRegistry.fetchNextProcessChain(
          SubmissionRegistry.ProcessChainStatus.REGISTERED,
          SubmissionRegistry.ProcessChainStatus.REGISTERED)
      val runningPc1 = submissionRegistry.fetchNextProcessChain(
          SubmissionRegistry.ProcessChainStatus.RUNNING,
          SubmissionRegistry.ProcessChainStatus.RUNNING)
      val pcStatus1 = submissionRegistry.getProcessChainStatus(pc.id)

      ctx.verify {
        assertThat(pcs)
            .hasSize(1)
            .contains(pc)
        assertThat(registeredPc1)
            .isEqualTo(pc)
        assertThat(runningPc1)
            .isNull()
        assertThat(pcStatus1)
            .isEqualTo(SubmissionRegistry.ProcessChainStatus.REGISTERED)
      }

      submissionRegistry.setProcessChainStatus(pc.id,
          SubmissionRegistry.ProcessChainStatus.RUNNING)

      val registeredPc2 = submissionRegistry.fetchNextProcessChain(
          SubmissionRegistry.ProcessChainStatus.REGISTERED,
          SubmissionRegistry.ProcessChainStatus.REGISTERED)
      val runningPc2 = submissionRegistry.fetchNextProcessChain(
          SubmissionRegistry.ProcessChainStatus.RUNNING,
          SubmissionRegistry.ProcessChainStatus.RUNNING)
      val pcStatus2 = submissionRegistry.getProcessChainStatus(pc.id)

      ctx.verify {
        assertThat(registeredPc2)
            .isNull()
        assertThat(runningPc2)
            .isEqualTo(pc)
        assertThat(pcStatus2)
            .isEqualTo(SubmissionRegistry.ProcessChainStatus.RUNNING)
      }

      ctx.completeNow()
    }
  }

  @Test
  fun getStatusOfMissingProcessChain(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      try {
        submissionRegistry.getProcessChainStatus("MISSING")
        throw NoStackTraceThrowable("getProcessChainStatus should throw")
      } catch (e: NoSuchElementException) {
        ctx.completeNow()
      } catch (e: Throwable) {
        ctx.failNow(e)
      }
    }
  }

  @Test
  fun setProcessChainOutput(vertx: Vertx, ctx: VertxTestContext) {
    val s = Submission(workflow = Workflow())
    val pc = ProcessChain()

    GlobalScope.launch(vertx.dispatcher()) {
      submissionRegistry.addSubmission(s)
      submissionRegistry.addProcessChains(listOf(pc), s.id)
      val pcOutput1 = submissionRegistry.getProcessChainOutput(pc.id)

      ctx.verify {
        assertThat(pcOutput1).isNull()
      }

      val output = mapOf("ARG1" to listOf("output.txt"))
      submissionRegistry.setProcessChainOutput(pc.id, output)
      val pcOutput2 = submissionRegistry.getProcessChainOutput(pc.id)

      ctx.verify {
        assertThat(pcOutput2).isEqualTo(output)
      }

      ctx.completeNow()
    }
  }

  @Test
  fun getOutputOfMissingProcessChain(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      try {
        submissionRegistry.getProcessChainOutput("MISSING")
        throw NoStackTraceThrowable("getProcessChainOutput should throw")
      } catch (e: NoSuchElementException) {
        ctx.completeNow()
      } catch (e: Throwable) {
        ctx.failNow(e)
      }
    }
  }
}
