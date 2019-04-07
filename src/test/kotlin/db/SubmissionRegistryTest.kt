package db

import assertThatThrownBy
import coVerify
import io.vertx.core.Vertx
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
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
import java.time.Instant

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
  fun findSubmissionIdsByStatus(vertx: Vertx, ctx: VertxTestContext) {
    val s1 = Submission(workflow = Workflow(), status = Submission.Status.RUNNING)
    val s2 = Submission(workflow = Workflow(), status = Submission.Status.SUCCESS)
    val s3 = Submission(workflow = Workflow(), status = Submission.Status.RUNNING)

    GlobalScope.launch(vertx.dispatcher()) {
      submissionRegistry.addSubmission(s1)
      submissionRegistry.addSubmission(s2)
      submissionRegistry.addSubmission(s3)

      val ids = submissionRegistry.findSubmissionIdsByStatus(Submission.Status.RUNNING)
      ctx.verify {
        assertThat(ids).hasSize(2)
        assertThat(ids).contains(s1.id)
        assertThat(ids).contains(s3.id)
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
  fun setSubmissionStartTime(vertx: Vertx, ctx: VertxTestContext) {
    val s = Submission(workflow = Workflow())

    GlobalScope.launch(vertx.dispatcher()) {
      submissionRegistry.addSubmission(s)
      val s1 = submissionRegistry.findSubmissionById(s.id)

      ctx.verify {
        assertThat(s1).isEqualTo(s)
      }

      val startTime = Instant.now()
      submissionRegistry.setSubmissionStartTime(s.id, startTime)
      val s2 = submissionRegistry.findSubmissionById(s.id)

      ctx.verify {
        assertThat(s2).isEqualTo(s.copy(startTime = startTime))
      }

      ctx.completeNow()
    }
  }

  @Test
  fun setSubmissionEndTime(vertx: Vertx, ctx: VertxTestContext) {
    val s = Submission(workflow = Workflow())

    GlobalScope.launch(vertx.dispatcher()) {
      submissionRegistry.addSubmission(s)
      val s1 = submissionRegistry.findSubmissionById(s.id)

      ctx.verify {
        assertThat(s1).isEqualTo(s)
      }

      val endTime = Instant.now()
      submissionRegistry.setSubmissionEndTime(s.id, endTime)
      val s2 = submissionRegistry.findSubmissionById(s.id)

      ctx.verify {
        assertThat(s2).isEqualTo(s.copy(endTime = endTime))
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
  fun getSubmissionStatus(vertx: Vertx, ctx: VertxTestContext) {
    val s1 = Submission(workflow = Workflow(), status = Submission.Status.RUNNING)
    val s2 = Submission(workflow = Workflow(), status = Submission.Status.SUCCESS)
    val s3 = Submission(workflow = Workflow(), status = Submission.Status.ERROR)

    GlobalScope.launch(vertx.dispatcher()) {
      submissionRegistry.addSubmission(s1)
      submissionRegistry.addSubmission(s2)
      submissionRegistry.addSubmission(s3)

      val status1 = submissionRegistry.getSubmissionStatus(s1.id)
      val status2 = submissionRegistry.getSubmissionStatus(s2.id)
      val status3 = submissionRegistry.getSubmissionStatus(s3.id)
      ctx.verify {
        assertThat(status1).isEqualTo(s1.status)
        assertThat(status2).isEqualTo(s2.status)
        assertThat(status3).isEqualTo(s3.status)
      }

      submissionRegistry.setSubmissionStatus(s1.id, Submission.Status.ACCEPTED)
      val status4 = submissionRegistry.getSubmissionStatus(s1.id)
      ctx.verify {
        assertThat(status4).isEqualTo(Submission.Status.ACCEPTED)
      }

      ctx.completeNow()
    }
  }

  @Test
  fun setSubmissionExecutionState(vertx: Vertx, ctx: VertxTestContext) {
    val s = Submission(workflow = Workflow())

    GlobalScope.launch(vertx.dispatcher()) {
      submissionRegistry.addSubmission(s)

      ctx.coVerify {
        assertThat(submissionRegistry.getSubmissionExecutionState(s.id)).isNull()

        val state = json {
          obj(
              "actions" to array()
          )
        }

        submissionRegistry.setSubmissionExecutionState(s.id, state)
        assertThat(submissionRegistry.getSubmissionExecutionState(s.id)).isEqualTo(state)

        submissionRegistry.setSubmissionExecutionState(s.id, null)
        assertThat(submissionRegistry.getSubmissionExecutionState(s.id)).isNull()
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
      val registeredPc = submissionRegistry.findProcessChainById(pc.id)

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
  fun countProcessChainsBySubmissionId(vertx: Vertx, ctx: VertxTestContext) {
    val s = Submission(workflow = Workflow())
    val pc1 = ProcessChain()
    val pc2 = ProcessChain()

    GlobalScope.launch(vertx.dispatcher()) {
      submissionRegistry.addSubmission(s)
      val count0 = submissionRegistry.countProcessChainsBySubmissionId(s.id)
      submissionRegistry.addProcessChains(listOf(pc1, pc2), s.id)
      val count2 = submissionRegistry.countProcessChainsBySubmissionId(s.id)

      ctx.verify {
        assertThat(count0).isEqualTo(0)
        assertThat(count2).isEqualTo(2)
      }

      ctx.completeNow()
    }
  }

  @Test
  fun countProcessChainsByStatus(vertx: Vertx, ctx: VertxTestContext) {
    val s = Submission(workflow = Workflow())
    val pc1 = ProcessChain()
    val pc2 = ProcessChain()
    val pc3 = ProcessChain()
    val pc4 = ProcessChain()

    GlobalScope.launch(vertx.dispatcher()) {
      submissionRegistry.addSubmission(s)
      val count0 = submissionRegistry.countProcessChainsByStatus(s.id,
          SubmissionRegistry.ProcessChainStatus.REGISTERED)
      submissionRegistry.addProcessChains(listOf(pc1), s.id)
      val count1 = submissionRegistry.countProcessChainsByStatus(s.id,
          SubmissionRegistry.ProcessChainStatus.REGISTERED)
      submissionRegistry.addProcessChains(listOf(pc2, pc3), s.id,
          SubmissionRegistry.ProcessChainStatus.RUNNING)
      val count2 = submissionRegistry.countProcessChainsByStatus(s.id,
          SubmissionRegistry.ProcessChainStatus.REGISTERED)
      val count3 = submissionRegistry.countProcessChainsByStatus(s.id,
          SubmissionRegistry.ProcessChainStatus.RUNNING)
      submissionRegistry.addProcessChains(listOf(pc4), s.id,
          SubmissionRegistry.ProcessChainStatus.SUCCESS)
      val count4 = submissionRegistry.countProcessChainsByStatus(s.id,
          SubmissionRegistry.ProcessChainStatus.REGISTERED)
      val count5 = submissionRegistry.countProcessChainsByStatus(s.id,
          SubmissionRegistry.ProcessChainStatus.RUNNING)
      val count6 = submissionRegistry.countProcessChainsByStatus(s.id,
          SubmissionRegistry.ProcessChainStatus.SUCCESS)

      ctx.verify {
        assertThat(count0).isEqualTo(0)
        assertThat(count1).isEqualTo(1)
        assertThat(count2).isEqualTo(1)
        assertThat(count3).isEqualTo(2)
        assertThat(count4).isEqualTo(1)
        assertThat(count5).isEqualTo(2)
        assertThat(count6).isEqualTo(1)
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
  fun getProcessChainSubmissionId(vertx: Vertx, ctx: VertxTestContext) {
    val s = Submission(workflow = Workflow())
    val pc = ProcessChain()

    GlobalScope.launch(vertx.dispatcher()) {
      submissionRegistry.addSubmission(s)
      submissionRegistry.addProcessChains(listOf(pc), s.id)
      val sid = submissionRegistry.getProcessChainSubmissionId(pc.id)
      ctx.verify {
        assertThat(sid).isEqualTo(s.id)
      }

      ctx.completeNow()
    }
  }

  @Test
  fun getSubmissionIdOfMissingProcessChain(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        assertThatThrownBy {
          submissionRegistry.getProcessChainSubmissionId("MISSING")
        }.isInstanceOf(NoSuchElementException::class.java)
      }
      ctx.completeNow()
    }
  }

  @Test
  fun addProcessChainToMissingSubmission(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        assertThatThrownBy {
          submissionRegistry.addProcessChains(listOf(ProcessChain()), "MISSING")
        }.isInstanceOf(NoSuchElementException::class.java)
        ctx.completeNow()
      }
    }
  }

  @Test
  fun setProcessChainStartTime(vertx: Vertx, ctx: VertxTestContext) {
    val s = Submission(workflow = Workflow())
    val pc = ProcessChain()

    GlobalScope.launch(vertx.dispatcher()) {
      submissionRegistry.addSubmission(s)
      submissionRegistry.addProcessChains(listOf(pc), s.id)
      val startTime1 = submissionRegistry.getProcessChainStartTime(pc.id)

      ctx.verify {
        assertThat(startTime1).isNull()
      }

      val newStartTime = Instant.now()
      submissionRegistry.setProcessChainStartTime(pc.id, newStartTime)
      val startTime2 = submissionRegistry.getProcessChainStartTime(pc.id)

      ctx.verify {
        assertThat(startTime2).isEqualTo(newStartTime)
      }

      submissionRegistry.setProcessChainStartTime(pc.id, null)
      val startTime3 = submissionRegistry.getProcessChainStartTime(pc.id)

      ctx.verify {
        assertThat(startTime3).isNull()
      }

      ctx.completeNow()
    }
  }

  @Test
  fun setProcessChainEndTime(vertx: Vertx, ctx: VertxTestContext) {
    val s = Submission(workflow = Workflow())
    val pc = ProcessChain()

    GlobalScope.launch(vertx.dispatcher()) {
      submissionRegistry.addSubmission(s)
      submissionRegistry.addProcessChains(listOf(pc), s.id)
      val endTime1 = submissionRegistry.getProcessChainEndTime(pc.id)

      ctx.verify {
        assertThat(endTime1).isNull()
      }

      val newEndTime = Instant.now()
      submissionRegistry.setProcessChainEndTime(pc.id, newEndTime)
      val endTime2 = submissionRegistry.getProcessChainEndTime(pc.id)

      ctx.verify {
        assertThat(endTime2).isEqualTo(newEndTime)
      }

      submissionRegistry.setProcessChainEndTime(pc.id, null)
      val endTime3 = submissionRegistry.getProcessChainEndTime(pc.id)

      ctx.verify {
        assertThat(endTime3).isNull()
      }

      ctx.completeNow()
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
      ctx.coVerify {
        assertThatThrownBy {
          submissionRegistry.getProcessChainStatus("MISSING")
        }.isInstanceOf(NoSuchElementException::class.java)
        ctx.completeNow()
      }
    }
  }

  private suspend fun doSetProcessChainResults(ctx: VertxTestContext): ProcessChain {
    val s = Submission(workflow = Workflow())
    val pc = ProcessChain()

    submissionRegistry.addSubmission(s)
    submissionRegistry.addProcessChains(listOf(pc), s.id)
    val pcResults1 = submissionRegistry.getProcessChainResults(pc.id)

    ctx.verify {
      assertThat(pcResults1).isNull()
    }

    val results = mapOf("ARG1" to listOf("output.txt"))
    submissionRegistry.setProcessChainResults(pc.id, results)
    val pcResults2 = submissionRegistry.getProcessChainResults(pc.id)

    ctx.verify {
      assertThat(pcResults2).isEqualTo(results)
    }

    return pc
  }

  @Test
  fun setProcessChainResults(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      doSetProcessChainResults(ctx)
      ctx.completeNow()
    }
  }

  @Test
  fun resetProcessChainResults(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val pc = doSetProcessChainResults(ctx)

      submissionRegistry.setProcessChainResults(pc.id, null)
      val pcResults = submissionRegistry.getProcessChainResults(pc.id)

      ctx.verify {
        assertThat(pcResults).isNull()
      }

      ctx.completeNow()
    }
  }

  @Test
  fun getResultsOfMissingProcessChain(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        assertThatThrownBy {
          submissionRegistry.getProcessChainResults("MISSING")
        }.isInstanceOf(NoSuchElementException::class.java)
        ctx.completeNow()
      }
    }
  }

  private suspend fun doSetProcessChainErrorMessage(ctx: VertxTestContext): ProcessChain {
    val s = Submission(workflow = Workflow())
    val pc = ProcessChain()

    submissionRegistry.addSubmission(s)
    submissionRegistry.addProcessChains(listOf(pc), s.id)
    val pcErrorMessage1 = submissionRegistry.getProcessChainErrorMessage(pc.id)

    ctx.verify {
      assertThat(pcErrorMessage1).isNull()
    }

    val errorMessage = "THIS is an ERROR!!!!"
    submissionRegistry.setProcessChainErrorMessage(pc.id, errorMessage)
    val pcErrorMessage2 = submissionRegistry.getProcessChainErrorMessage(pc.id)

    ctx.verify {
      assertThat(pcErrorMessage2).isEqualTo(errorMessage)
    }

    return pc
  }

  @Test
  fun setProcessChainErrorMessage(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      doSetProcessChainErrorMessage(ctx)
      ctx.completeNow()
    }
  }

  @Test
  fun resetProcessChainErrorMessage(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val pc = doSetProcessChainErrorMessage(ctx)

      submissionRegistry.setProcessChainErrorMessage(pc.id, null)
      val pcErrorMessage = submissionRegistry.getProcessChainErrorMessage(pc.id)

      ctx.verify {
        assertThat(pcErrorMessage).isNull()
      }

      ctx.completeNow()
    }
  }

  @Test
  fun getErrorMessageOfMissingProcessChain(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        assertThatThrownBy {
          submissionRegistry.getProcessChainErrorMessage("MISSING")
        }.isInstanceOf(NoSuchElementException::class.java)
        ctx.completeNow()
      }
    }
  }
}
