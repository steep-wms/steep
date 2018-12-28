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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Tests for all [SubmissionRegistry] implementations
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
abstract class SubmissionRegistryTest {
  abstract val submissionRegistry: SubmissionRegistry

  @Test
  fun addSubmission(vertx: Vertx, ctx: VertxTestContext) {
    val s = Submission()

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
  fun addProcessChain(vertx: Vertx, ctx: VertxTestContext) {
    val s = Submission()
    val pc = ProcessChain()

    GlobalScope.launch(vertx.dispatcher()) {
      submissionRegistry.addSubmission(s)
      submissionRegistry.addProcessChain(pc, s.id)
      val pcs = submissionRegistry.findProcessChainsBySubmissionId(s.id)
      val registeredPcs = submissionRegistry.findProcessChainsByStatus(
          SubmissionRegistry.ProcessChainStatus.REGISTERED)

      ctx.verify {
        assertThat(pcs)
            .hasSize(1)
            .contains(pc)
        assertThat(registeredPcs)
            .hasSize(1)
            .contains(pc)
      }

      ctx.completeNow()
    }
  }

  @Test
  fun addProcessChainToMissingSubmission(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      try {
        submissionRegistry.addProcessChain(ProcessChain(), "MISSING")
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
    val s = Submission()
    val pc = ProcessChain()

    GlobalScope.launch(vertx.dispatcher()) {
      submissionRegistry.addSubmission(s)
      submissionRegistry.addProcessChain(pc, s.id)
      val pcs = submissionRegistry.findProcessChainsBySubmissionId(s.id)
      val registeredPcs1 = submissionRegistry.findProcessChainsByStatus(
          SubmissionRegistry.ProcessChainStatus.REGISTERED)
      val runningPcs1 = submissionRegistry.findProcessChainsByStatus(
          SubmissionRegistry.ProcessChainStatus.RUNNING)
      val pcStatus1 = submissionRegistry.getProcessChainStatus(pc.id)

      ctx.verify {
        assertThat(pcs)
            .hasSize(1)
            .contains(pc)
        assertThat(registeredPcs1)
            .hasSize(1)
            .contains(pc)
        assertThat(runningPcs1)
            .isEmpty()
        assertThat(pcStatus1)
            .isEqualTo(SubmissionRegistry.ProcessChainStatus.REGISTERED)
      }

      submissionRegistry.setProcessChainStatus(pc.id,
          SubmissionRegistry.ProcessChainStatus.RUNNING)

      val registeredPcs2 = submissionRegistry.findProcessChainsByStatus(
          SubmissionRegistry.ProcessChainStatus.REGISTERED)
      val runningPcs2 = submissionRegistry.findProcessChainsByStatus(
          SubmissionRegistry.ProcessChainStatus.RUNNING)
      val pcStatus2 = submissionRegistry.getProcessChainStatus(pc.id)

      ctx.verify {
        assertThat(registeredPcs2)
            .isEmpty()
        assertThat(runningPcs2)
            .hasSize(1)
            .contains(pc)
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
    val s = Submission()
    val pc = ProcessChain()

    GlobalScope.launch(vertx.dispatcher()) {
      submissionRegistry.addSubmission(s)
      submissionRegistry.addProcessChain(pc, s.id)
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
