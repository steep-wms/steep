package model.plugins

import coVerify
import io.vertx.core.Vertx
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import model.processchain.Executable
import model.processchain.ProcessChain
import model.workflow.Workflow
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.reflect.full.callSuspend

/**
 * Unit tests for [Plugin]-related functions
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class PluginTest {
  class FunctionHolder(private val vertx: Vertx, private val ctx: VertxTestContext) {
    fun f(injectedCtx: VertxTestContext, injectedVertx: Vertx) {
      ctx.verify {
        assertThat(injectedCtx).isSameAs(ctx)
        assertThat(injectedVertx).isSameAs(vertx)
        injectedCtx.completeNow()
      }
    }
  }

  class FunctionHolderSuspend(private val vertx: Vertx, private val ctx: VertxTestContext) {
    suspend fun f(injectedCtx: VertxTestContext, injectedVertx: Vertx) {
      ctx.coVerify {
        assertThat(injectedCtx).isSameAs(ctx)
        assertThat(injectedVertx).isSameAs(vertx)
        injectedCtx.completeNow()
      }
    }
  }

  class FunctionHolderLists(private val vertx: Vertx,
      private val ctx: VertxTestContext,
      private val processChains: List<ProcessChain>,
      private val executables: List<Executable>) {
    fun f(injectedCtx: VertxTestContext, injectedVertx: Vertx,
        injectedProcessChains: List<ProcessChain>,
        injectedExecutables: List<Executable>) {
      ctx.verify {
        assertThat(injectedCtx).isSameAs(ctx)
        assertThat(injectedVertx).isSameAs(vertx)
        assertThat(injectedProcessChains).isSameAs(processChains)
        assertThat(injectedExecutables).isSameAs(executables)
        injectedCtx.completeNow()
      }
    }
  }

  class FunctionHolderCovariance(private val vertx: Vertx,
      private val ctx: VertxTestContext,
      private val processChains: List<ProcessChain>,
      private val executables: List<Executable>,
      private val inputStream: ByteArrayInputStream) {
    fun f(injectedCtx: VertxTestContext, injectedVertx: Vertx,
        injectedProcessChains: Collection<ProcessChain>,
        injectedExecutables: Collection<Executable>,
        injectedInputStream: InputStream) {
      ctx.verify {
        assertThat(injectedCtx).isSameAs(ctx)
        assertThat(injectedVertx).isSameAs(vertx)
        assertThat(injectedProcessChains).isSameAs(processChains)
        assertThat(injectedExecutables).isSameAs(executables)
        assertThat(injectedInputStream).isSameAs(inputStream)
        injectedCtx.completeNow()
      }
    }
  }

  @Suppress("UNUSED_PARAMETER")
  private fun templateNormalOrder(ctx: VertxTestContext, vertx: Vertx) {
    throw NotImplementedError()
  }

  @Suppress("UNUSED_PARAMETER")
  private fun templateReverseOrder(vertx: Vertx, ctx: VertxTestContext) {
    throw NotImplementedError()
  }

  @Suppress("UNUSED_PARAMETER")
  private fun templateTooMany(vertx: Vertx, ctx: VertxTestContext, unnecessary: Workflow) {
    throw NotImplementedError()
  }

  @Suppress("UNUSED_PARAMETER")
  private fun templateUnknownParameter(vertx: Vertx) {
    throw NotImplementedError()
  }

  @Suppress("UNUSED_PARAMETER")
  private fun templateLists(ctx: VertxTestContext, vertx: Vertx,
      executables: List<Executable>, processChains: List<ProcessChain>,
      inputStream: ByteArrayInputStream) {
    throw NotImplementedError()
  }

  /**
   * Test if [FunctionHolder.f] can be called through a wrapper function
   */
  @Test
  fun wrapPluginFunctionNormalOrder(vertx: Vertx, ctx: VertxTestContext) {
    val h = FunctionHolder(vertx, ctx)
    CoroutineScope(vertx.dispatcher()).launch {
      val wrapped = wrapPluginFunction(h::f, ::templateNormalOrder.parameters)
      wrapped.call(ctx, vertx)
    }
  }

  /**
   * Test if [FunctionHolder.f] can be called through a wrapper function if
   * the arguments are provided in reverse order.
   */
  @Test
  fun wrapPluginFunctionReverseOrder(vertx: Vertx, ctx: VertxTestContext) {
    val h = FunctionHolder(vertx, ctx)
    CoroutineScope(vertx.dispatcher()).launch {
      val wrapped = wrapPluginFunction(h::f, ::templateReverseOrder.parameters)
      wrapped.call(vertx, ctx)
    }
  }

  /**
   * Test if [FunctionHolder.f] can be called through a wrapper function even
   * if too many arguments are provided.
   */
  @Test
  fun wrapPluginFunctionTooMany(vertx: Vertx, ctx: VertxTestContext) {
    val h = FunctionHolder(vertx, ctx)
    CoroutineScope(vertx.dispatcher()).launch {
      val wrapped = wrapPluginFunction(h::f, ::templateTooMany.parameters)
      wrapped.call(vertx, ctx, Workflow())
    }
  }

  /**
   * Test if [wrapPluginFunction] throws an exception if the function expects
   * an unknown parameter.
   */
  @Test
  fun wrapPluginFunctionUnknownParameter(vertx: Vertx, ctx: VertxTestContext) {
    val h = FunctionHolder(vertx, ctx)
    CoroutineScope(vertx.dispatcher()).launch {
      assertThatThrownBy {
        wrapPluginFunction(h::f, ::templateUnknownParameter.parameters)
      }.isInstanceOf(IllegalArgumentException::class.java)
          .hasMessageContainingAll("injectedCtx", "VertxTestContext")
      ctx.completeNow()
    }
  }

  /**
   * Test if a suspend function can be wrapped
   */
  @Test
  fun wrapPluginFunctionSuspend(vertx: Vertx, ctx: VertxTestContext) {
    val h = FunctionHolderSuspend(vertx, ctx)
    CoroutineScope(vertx.dispatcher()).launch {
      val wrapped = wrapPluginFunction(h::f, ::templateReverseOrder.parameters)
      assertThat(wrapped.isSuspend).isTrue
      wrapped.callSuspend(vertx, ctx)
    }
  }

  /**
   * Test if a suspend function can be wrapped even if too many
   * parameters are provided
   */
  @Test
  fun wrapPluginFunctionSuspendTooMany(vertx: Vertx, ctx: VertxTestContext) {
    val h = FunctionHolderSuspend(vertx, ctx)
    CoroutineScope(vertx.dispatcher()).launch {
      val wrapped = wrapPluginFunction(h::f, ::templateTooMany.parameters)
      assertThat(wrapped.isSuspend).isTrue
      wrapped.callSuspend(vertx, ctx, Workflow())
    }
  }

  /**
   * Test if a function with list parameters can be wrapped
   */
  @Test
  fun wrapPluginFunctionLists(vertx: Vertx, ctx: VertxTestContext) {
    val processChains = listOf(ProcessChain())
    val executables = listOf(Executable(path = "path", arguments = emptyList()))
    val h = FunctionHolderLists(vertx, ctx, processChains, executables)
    CoroutineScope(vertx.dispatcher()).launch {
      val wrapped = wrapPluginFunction(h::f, ::templateLists.parameters)
      wrapped.callSuspend(ctx, vertx, executables, processChains)
    }
  }

  /**
   * Test if a function with collection parameters can be wrapped with a
   * function signature accepting lists
   */
  @Test
  fun wrapPluginFunctionCovariance(vertx: Vertx, ctx: VertxTestContext) {
    val processChains = listOf(ProcessChain())
    val executables = listOf(Executable(path = "path", arguments = emptyList()))
    val bais = ByteArrayInputStream(ByteArray(0))
    val h = FunctionHolderCovariance(vertx, ctx, processChains, executables, bais)
    CoroutineScope(vertx.dispatcher()).launch {
      val wrapped = wrapPluginFunction(h::f, ::templateLists.parameters)
      wrapped.callSuspend(ctx, vertx, executables, processChains, bais)
    }
  }
}
