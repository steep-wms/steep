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
import org.junit.jupiter.api.AfterEach
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

  @AfterEach
  open fun tearDown(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      submissionRegistry.close()
      ctx.completeNow()
    }
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
  fun findSubmissionByIdNull(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val s = submissionRegistry.findSubmissionById("DOES_NOT_EXIST")
      ctx.verify {
        assertThat(s).isNull()
      }
      ctx.completeNow()
    }
  }

  @Test
  fun findSubmissionsPage(vertx: Vertx, ctx: VertxTestContext) {
    val s1 = Submission(workflow = Workflow(), status = Submission.Status.RUNNING)
    val s2 = Submission(workflow = Workflow(), status = Submission.Status.SUCCESS)
    val s3 = Submission(workflow = Workflow(), status = Submission.Status.RUNNING)

    GlobalScope.launch(vertx.dispatcher()) {
      submissionRegistry.addSubmission(s1)
      submissionRegistry.addSubmission(s2)
      submissionRegistry.addSubmission(s3)

      // check if order is correct
      val r1 = submissionRegistry.findSubmissions()
      ctx.verify {
        assertThat(r1).isEqualTo(listOf(s1, s2, s3))
      }

      // check if order can be reversed
      val r2 = submissionRegistry.findSubmissions(order = -1)
      ctx.verify {
        assertThat(r2).isEqualTo(listOf(s3, s2, s1))
      }

      // check if we can query pages
      val r3 = submissionRegistry.findSubmissions(size = 1, offset = 0)
      val r4 = submissionRegistry.findSubmissions(size = 2, offset = 1)
      ctx.verify {
        assertThat(r3).isEqualTo(listOf(s1))
        assertThat(r4).isEqualTo(listOf(s2, s3))
      }

      // check if we can query pages with reversed order
      val r5 = submissionRegistry.findSubmissions(size = 1, offset = 0, order = -1)
      val r6 = submissionRegistry.findSubmissions(size = 2, offset = 1, order = -1)
      ctx.verify {
        assertThat(r5).isEqualTo(listOf(s3))
        assertThat(r6).isEqualTo(listOf(s2, s1))
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
  fun countSubmissions(vertx: Vertx, ctx: VertxTestContext) {
    val s1 = Submission(workflow = Workflow(), status = Submission.Status.RUNNING)
    val s2 = Submission(workflow = Workflow(), status = Submission.Status.SUCCESS)
    val s3 = Submission(workflow = Workflow(), status = Submission.Status.RUNNING)

    GlobalScope.launch(vertx.dispatcher()) {
      val c1 = submissionRegistry.countSubmissions()
      ctx.verify {
        assertThat(c1).isEqualTo(0)
      }

      submissionRegistry.addSubmission(s1)
      submissionRegistry.addSubmission(s2)
      val c2 = submissionRegistry.countSubmissions()
      ctx.verify {
        assertThat(c2).isEqualTo(2)
      }

      submissionRegistry.addSubmission(s3)
      val c3 = submissionRegistry.countSubmissions()
      ctx.verify {
        assertThat(c3).isEqualTo(3)
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

  private suspend fun doSetSubmissionResults(ctx: VertxTestContext,
      results: Map<String, List<Any>> = mapOf("ARG1" to listOf("output.txt"))): Submission {
    val s = Submission(workflow = Workflow())

    submissionRegistry.addSubmission(s)
    val results1 = submissionRegistry.getSubmissionResults(s.id)

    ctx.verify {
      assertThat(results1).isNull()
    }

    submissionRegistry.setSubmissionResults(s.id, results)
    val results2 = submissionRegistry.getSubmissionResults(s.id)

    ctx.verify {
      assertThat(results2).isEqualTo(results)
    }

    return s
  }

  @Test
  fun setSubmissionResults(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      doSetSubmissionResults(ctx)
      ctx.completeNow()
    }
  }

  @Test
  fun setSubmissionResultsNested(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      doSetSubmissionResults(ctx, mapOf("ARG1" to listOf(
          listOf("output1.txt", "output2.txt")
      )))
      ctx.completeNow()
    }
  }

  @Test
  fun resetSubmissionResults(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val s = doSetSubmissionResults(ctx)

      submissionRegistry.setSubmissionResults(s.id, null)
      val results = submissionRegistry.getSubmissionResults(s.id)

      ctx.verify {
        assertThat(results).isNull()
      }

      ctx.completeNow()
    }
  }

  @Test
  fun getResultsOfMissingSubmission(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        assertThatThrownBy {
          submissionRegistry.getSubmissionResults("MISSING")
        }.isInstanceOf(NoSuchElementException::class.java)
        ctx.completeNow()
      }
    }
  }

  private suspend fun doSetSubmissionErrorMessage(ctx: VertxTestContext): Submission {
    val s = Submission(workflow = Workflow())

    submissionRegistry.addSubmission(s)
    val errorMessage1 = submissionRegistry.getSubmissionErrorMessage(s.id)

    ctx.verify {
      assertThat(errorMessage1).isNull()
    }

    val errorMessage = "THIS is an ERROR!!!!"
    submissionRegistry.setSubmissionErrorMessage(s.id, errorMessage)
    val errorMessage2 = submissionRegistry.getSubmissionErrorMessage(s.id)

    ctx.verify {
      assertThat(errorMessage2).isEqualTo(errorMessage)
    }

    return s
  }

  @Test
  fun setSubmissionErrorMessage(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      doSetSubmissionErrorMessage(ctx)
      ctx.completeNow()
    }
  }

  @Test
  fun resetSubmissionErrorMessage(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val s = doSetSubmissionErrorMessage(ctx)

      submissionRegistry.setSubmissionErrorMessage(s.id, null)
      val errorMessage = submissionRegistry.getSubmissionErrorMessage(s.id)

      ctx.verify {
        assertThat(errorMessage).isNull()
      }

      ctx.completeNow()
    }
  }

  @Test
  fun getErrorMessageOfMissingSubmission(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        assertThatThrownBy {
          submissionRegistry.getSubmissionErrorMessage("MISSING")
        }.isInstanceOf(NoSuchElementException::class.java)
        ctx.completeNow()
      }
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
  fun findProcessChainsPage(vertx: Vertx, ctx: VertxTestContext) {
    val s1 = Submission(workflow = Workflow(), status = Submission.Status.RUNNING)
    val pc11 = ProcessChain()
    val pc12 = ProcessChain()
    val s2 = Submission(workflow = Workflow(), status = Submission.Status.SUCCESS)
    val pc21 = ProcessChain()

    GlobalScope.launch(vertx.dispatcher()) {
      submissionRegistry.addSubmission(s1)
      submissionRegistry.addProcessChains(listOf(pc11, pc12), s1.id)
      submissionRegistry.addSubmission(s2)
      submissionRegistry.addProcessChains(listOf(pc21), s2.id)

      // check if order is correct
      val r1 = submissionRegistry.findProcessChains()
      ctx.verify {
        assertThat(r1).isEqualTo(listOf(Pair(pc11, s1.id), Pair(pc12, s1.id), Pair(pc21, s2.id)))
      }

      // check if order can be reversed
      val r2 = submissionRegistry.findProcessChains(order = -1)
      ctx.verify {
        assertThat(r2).isEqualTo(listOf(Pair(pc21, s2.id), Pair(pc12, s1.id), Pair(pc11, s1.id)))
      }

      // check if we can query pages
      val r3 = submissionRegistry.findProcessChains(size = 1, offset = 0)
      val r4 = submissionRegistry.findProcessChains(size = 2, offset = 1)
      ctx.verify {
        assertThat(r3).isEqualTo(listOf(Pair(pc11, s1.id)))
        assertThat(r4).isEqualTo(listOf(Pair(pc12, s1.id), Pair(pc21, s2.id)))
      }

      // check if we can query pages with reversed order
      val r5 = submissionRegistry.findProcessChains(size = 1, offset = 0, order = -1)
      val r6 = submissionRegistry.findProcessChains(size = 2, offset = 1, order = -1)
      ctx.verify {
        assertThat(r5).isEqualTo(listOf(Pair(pc21, s2.id)))
        assertThat(r6).isEqualTo(listOf(Pair(pc12, s1.id), Pair(pc11, s1.id)))
      }

      ctx.completeNow()
    }
  }

  @Test
  fun findProcessChainsBySubmissionPage(vertx: Vertx, ctx: VertxTestContext) {
    val s1 = Submission(workflow = Workflow(), status = Submission.Status.RUNNING)
    val pc1 = ProcessChain()
    val pc2 = ProcessChain()
    val pc3 = ProcessChain()
    val s2 = Submission(workflow = Workflow(), status = Submission.Status.RUNNING)
    val pc4 = ProcessChain()

    GlobalScope.launch(vertx.dispatcher()) {
      submissionRegistry.addSubmission(s1)
      submissionRegistry.addSubmission(s2)
      submissionRegistry.addProcessChains(listOf(pc1, pc2, pc3), s1.id)
      submissionRegistry.addProcessChains(listOf(pc4), s2.id)

      // check if order is correct
      val r1 = submissionRegistry.findProcessChainsBySubmissionId(s1.id)
      ctx.verify {
        assertThat(r1).isEqualTo(listOf(pc1, pc2, pc3))
      }

      // check if order can be reversed
      val r2 = submissionRegistry.findProcessChainsBySubmissionId(s1.id, order = -1)
      ctx.verify {
        assertThat(r2).isEqualTo(listOf(pc3, pc2, pc1))
      }

      // check if we can query pages
      val r3 = submissionRegistry.findProcessChainsBySubmissionId(s1.id, size = 1, offset = 0)
      val r4 = submissionRegistry.findProcessChainsBySubmissionId(s1.id, size = 2, offset = 1)
      ctx.verify {
        assertThat(r3).isEqualTo(listOf(pc1))
        assertThat(r4).isEqualTo(listOf(pc2, pc3))
      }

      // check if we can query pages with reversed order
      val r5 = submissionRegistry.findProcessChainsBySubmissionId(s1.id, size = 1, offset = 0, order = -1)
      val r6 = submissionRegistry.findProcessChainsBySubmissionId(s1.id, size = 2, offset = 1, order = -1)
      ctx.verify {
        assertThat(r5).isEqualTo(listOf(pc3))
        assertThat(r6).isEqualTo(listOf(pc2, pc1))
      }

      ctx.completeNow()
    }
  }

  @Test
  fun findProcessChainStatusesBySubmissionId(vertx: Vertx, ctx: VertxTestContext) {
    val s1 = Submission(workflow = Workflow())
    val pc1 = ProcessChain()
    val pc2 = ProcessChain()
    val pc3 = ProcessChain()
    val s2 = Submission(workflow = Workflow())
    val pc4 = ProcessChain()

    GlobalScope.launch(vertx.dispatcher()) {
      submissionRegistry.addSubmission(s1)
      submissionRegistry.addSubmission(s2)
      submissionRegistry.addProcessChains(listOf(pc1, pc2), s1.id,
          SubmissionRegistry.ProcessChainStatus.RUNNING)
      submissionRegistry.addProcessChains(listOf(pc3), s1.id,
          SubmissionRegistry.ProcessChainStatus.SUCCESS)
      submissionRegistry.addProcessChains(listOf(pc4), s2.id)

      val statuses1 = submissionRegistry.findProcessChainStatusesBySubmissionId(s1.id)
      val statuses2 = submissionRegistry.findProcessChainStatusesBySubmissionId(s2.id)
      ctx.verify {
        assertThat(statuses1).isEqualTo(mapOf(
            pc1.id to SubmissionRegistry.ProcessChainStatus.RUNNING,
            pc2.id to SubmissionRegistry.ProcessChainStatus.RUNNING,
            pc3.id to SubmissionRegistry.ProcessChainStatus.SUCCESS
        ))
        assertThat(statuses2).isEqualTo(mapOf(
            pc4.id to SubmissionRegistry.ProcessChainStatus.REGISTERED
        ))
      }

      ctx.completeNow()
    }
  }

  @Test
  fun countProcessChains(vertx: Vertx, ctx: VertxTestContext) {
    val s1 = Submission(workflow = Workflow(), status = Submission.Status.RUNNING)
    val pc11 = ProcessChain()
    val pc12 = ProcessChain()
    val s2 = Submission(workflow = Workflow(), status = Submission.Status.SUCCESS)
    val pc21 = ProcessChain()

    GlobalScope.launch(vertx.dispatcher()) {
      val r1 = submissionRegistry.countProcessChains()
      ctx.verify {
        assertThat(r1).isEqualTo(0)
      }

      submissionRegistry.addSubmission(s1)
      submissionRegistry.addProcessChains(listOf(pc11, pc12), s1.id)
      val r2 = submissionRegistry.countProcessChains()
      ctx.verify {
        assertThat(r2).isEqualTo(2)
      }

      submissionRegistry.addSubmission(s2)
      submissionRegistry.addProcessChains(listOf(pc21), s2.id)
      val r3 = submissionRegistry.countProcessChains()
      ctx.verify {
        assertThat(r3).isEqualTo(3)
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
  fun setAllProcessChainsStatus(vertx: Vertx, ctx: VertxTestContext) {
    val s1 = Submission(workflow = Workflow())
    val pc1 = ProcessChain()
    val pc2 = ProcessChain()
    val pc3 = ProcessChain()

    val s2 = Submission(workflow = Workflow())
    val pc4 = ProcessChain()
    val pc5 = ProcessChain()

    GlobalScope.launch(vertx.dispatcher()) {
      submissionRegistry.addSubmission(s1)
      submissionRegistry.addProcessChains(listOf(pc1), s1.id,
          SubmissionRegistry.ProcessChainStatus.RUNNING)
      submissionRegistry.addProcessChains(listOf(pc2, pc3), s1.id,
          SubmissionRegistry.ProcessChainStatus.REGISTERED)

      submissionRegistry.addSubmission(s2)
      submissionRegistry.addProcessChains(listOf(pc4, pc5), s2.id,
          SubmissionRegistry.ProcessChainStatus.REGISTERED)

      val pcStatus1a = submissionRegistry.getProcessChainStatus(pc1.id)
      val pcStatus2a = submissionRegistry.getProcessChainStatus(pc2.id)
      val pcStatus3a = submissionRegistry.getProcessChainStatus(pc3.id)
      val pcStatus4a = submissionRegistry.getProcessChainStatus(pc4.id)
      val pcStatus5a = submissionRegistry.getProcessChainStatus(pc5.id)

      ctx.verify {
        assertThat(pcStatus1a)
            .isEqualTo(SubmissionRegistry.ProcessChainStatus.RUNNING)
        assertThat(pcStatus2a)
            .isEqualTo(SubmissionRegistry.ProcessChainStatus.REGISTERED)
        assertThat(pcStatus3a)
            .isEqualTo(SubmissionRegistry.ProcessChainStatus.REGISTERED)
        assertThat(pcStatus4a)
            .isEqualTo(SubmissionRegistry.ProcessChainStatus.REGISTERED)
        assertThat(pcStatus5a)
            .isEqualTo(SubmissionRegistry.ProcessChainStatus.REGISTERED)
      }

      submissionRegistry.setAllProcessChainsStatus(s1.id,
          SubmissionRegistry.ProcessChainStatus.REGISTERED,
          SubmissionRegistry.ProcessChainStatus.CANCELLED)

      val pcStatus1b = submissionRegistry.getProcessChainStatus(pc1.id)
      val pcStatus2b = submissionRegistry.getProcessChainStatus(pc2.id)
      val pcStatus3b = submissionRegistry.getProcessChainStatus(pc3.id)
      val pcStatus4b = submissionRegistry.getProcessChainStatus(pc4.id)
      val pcStatus5b = submissionRegistry.getProcessChainStatus(pc5.id)

      ctx.verify {
        assertThat(pcStatus1b)
            .isEqualTo(SubmissionRegistry.ProcessChainStatus.RUNNING)
        assertThat(pcStatus2b)
            .isEqualTo(SubmissionRegistry.ProcessChainStatus.CANCELLED)
        assertThat(pcStatus3b)
            .isEqualTo(SubmissionRegistry.ProcessChainStatus.CANCELLED)
        assertThat(pcStatus4b)
            .isEqualTo(SubmissionRegistry.ProcessChainStatus.REGISTERED)
        assertThat(pcStatus5b)
            .isEqualTo(SubmissionRegistry.ProcessChainStatus.REGISTERED)
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

  private suspend fun doSetProcessChainResults(ctx: VertxTestContext,
      results: Map<String, List<Any>> = mapOf("ARG1" to listOf("output.txt"))): ProcessChain {
    val s = Submission(workflow = Workflow())
    val pc = ProcessChain()

    submissionRegistry.addSubmission(s)
    submissionRegistry.addProcessChains(listOf(pc), s.id)
    val pcResults1 = submissionRegistry.getProcessChainResults(pc.id)

    ctx.verify {
      assertThat(pcResults1).isNull()
    }

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
  fun setProcessChainResultsNested(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      doSetProcessChainResults(ctx, mapOf("ARG1" to listOf(
          listOf("output1.txt", "output2.txt")
      )))
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
