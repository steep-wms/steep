package agent

import AddressConstants.LOCAL_AGENT_ADDRESS_PREFIX
import AddressConstants.PROCESSCHAIN_PROGRESS_CHANGED
import ConfigConstants
import com.google.common.cache.CacheBuilder
import db.PluginRegistryFactory
import helper.DefaultOutputCollector
import helper.FileSystemUtils.readRecursive
import helper.LoggingOutputCollector
import helper.OutputCollector
import helper.UniqueID
import helper.withRetry
import io.prometheus.client.Gauge
import io.vertx.core.Context
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import model.metadata.Service
import model.plugins.call
import model.processchain.Argument
import model.processchain.ArgumentVariable
import model.processchain.Executable
import model.processchain.ProcessChain
import model.timeout.TimeoutPolicy
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import runtime.DockerRuntime
import runtime.OtherRuntime
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.round
import kotlin.reflect.full.callSuspend

/**
 * An agent that executes process chains locally
 * @param vertx the Vert.x instance
 * @dispatcher a coroutine dispatcher used to execute blocking process chains
 * in an asynchronous manner. Should be a [java.util.concurrent.ThreadPoolExecutor]
 * converted to a [CoroutineDispatcher] through [kotlinx.coroutines.asCoroutineDispatcher].
 * @author Michel Kraemer
 */
class LocalAgent(private val vertx: Vertx, val dispatcher: CoroutineDispatcher,
    private val config: JsonObject = vertx.orCreateContext.config()) : Agent, CoroutineScope {
  companion object {
    private val log = LoggerFactory.getLogger(LocalAgent::class.java)

    /**
     * A cache that tracks which directories we already created
     */
    private val mkdirCache = CacheBuilder.newBuilder()
        .expireAfterAccess(1, TimeUnit.MINUTES)
        .maximumSize(1000)
        .build<String, Boolean>()

    /**
     * The total number of times an executable with a given serviceId had
     * to be retried
     */
    private val gaugeRetries = Gauge.build()
        .name("steep_local_agent_retries")
        .labelNames("serviceId")
        .help("The total number of times an executable with a given " +
            "serviceId had to be retried")
        .register()

    /**
     * Prefix of the name of the logger that logs the output of process chains.
     * The process chain ID will be appended.
     */
    val PROCESSCHAIN_LOG_PREFIX = "${LocalAgent::class.java.name}.processChain."

    /**
     * Create a message for [TimeoutCancellationException] and [TimeoutException]
     */
    private fun makeTimeoutMessage(policy: TimeoutPolicy, type: String,
      serviceId: String?) = "Execution ${if (serviceId != null)
        "of service `$serviceId'" else ""} timed out after ${policy.timeout} ms ($type)"
  }

  override val id: String = UniqueID.next()

  override val coroutineContext: CoroutineContext by lazy {
    // use a SupervisorJob because executables may fail and we want to retry
    // them without cancelling the parent coroutine context
    dispatcher + SupervisorJob()
  }

  private val pluginRegistry = PluginRegistryFactory.create()
  private val outputLinesToCollect = config
      .getInteger(ConfigConstants.AGENT_OUTPUT_LINES_TO_COLLECT, 100)
  private val processChainLogsEnabled = config.getBoolean(
      ConfigConstants.LOGS_PROCESSCHAINS_ENABLED, false)

  private val otherRuntime by lazy { OtherRuntime() }
  private val dockerRuntime by lazy { DockerRuntime(config) }

  override suspend fun execute(processChain: ProcessChain): Map<String, List<Any>> {
    val outputs = processChain.executables
        .flatMap { it.arguments }
        .filter { it.type == Argument.Type.OUTPUT }
    val mkdirs = mkdirsForOutputs(outputs)
    var progress: Double? = null

    // temporarily register a consumer that can cancel the process chain
    val address = LOCAL_AGENT_ADDRESS_PREFIX + processChain.id
    val consumer = vertx.eventBus().consumer<JsonObject>(address).handler { msg ->
      when (msg.body().getString("action")) {
        "cancel" -> cancel()
        "getProgress" -> msg.reply(progress)
        else -> msg.fail(400, "Invalid action")
      }
    }

    // keep track of current progress and notify listeners
    fun setProgress(newProgress: Double) {
      val roundedNew = round(newProgress * 100) / 100.0
      val oldProgress = progress
      if (oldProgress == null) {
        progress = roundedNew
      } else if (roundedNew >= 0) {
        progress = roundedNew
      }
      if (progress != oldProgress) {
        vertx.eventBus().send(PROCESSCHAIN_PROGRESS_CHANGED, json {
          obj(
              "processChainId" to processChain.id,
              "estimatedProgress" to progress
          )
        })
      }
    }

    // create object that holds the current Vert.x context so runtimes which
    // are executed in another coroutine cotext can access it
    val contextWrapper = VertxContextWrapper(vertx)

    // execute the process chain
    val executor = Executors.newSingleThreadExecutor()
    try {
      // create all required output directories
      for (exec in mkdirs) {
        execute(exec, processChain.id, executor, contextWrapper)
      }

      // run executables and track progress
      // use `coroutineContext` so we are able to [cancel] the execution
      withContext(coroutineContext) {
        try {
          for ((index, exec) in processChain.executables.withIndex()) {
            withTimeout(exec.deadline, "deadline", exec.serviceId) {
              withRetry(exec.retries) { attempt ->
                if (attempt > 1) {
                  gaugeRetries.labels(exec.serviceId ?: "<unknown>").inc()
                }
                withTimeout(exec.maxRuntime, "maximum runtime", exec.serviceId) {
                  execute(exec, processChain.id, executor, contextWrapper) { p ->
                    val step = 1.0 / processChain.executables.size
                    setProgress(step * index + step * p)
                  }
                }
              }
            }

            if ((index + 1) < processChain.executables.size || progress != null) {
              setProgress((index + 1).toDouble() / processChain.executables.size)
            }
          }
        } catch (te: TimeoutException) {
          if (te.policy.errorOnTimeout) {
            throw te
          }
          cancel(TimeoutCancellationException(te.policy, te.type, te.serviceId))
        }
      }
    } finally {
      // make sure the consumer is unregistered
      consumer.unregister().await()

      // close executor
      executor.shutdown()
    }

    // create list of results
    val fs = vertx.fileSystem()
    return outputs.associate {
      val outputAdapter = pluginRegistry.findOutputAdapter(it.dataType)
      it.variable.id to (outputAdapter?.call(it, processChain, vertx) ?: (
        if (it.dataType == Argument.DATA_TYPE_DIRECTORY) {
          readRecursive(it.variable.value, fs)
        } else if (config.getBoolean(ConfigConstants.ONLYTRAVERSEDIRECTORYOUTPUTS, false)) {
          listOf(it.variable.value)
        } else {
          // this is not a directory and we cannot apply the new behavior
          if (!config.containsKey(ConfigConstants.ONLYTRAVERSEDIRECTORYOUTPUTS)) {
            log.warn("Your configuration does not include the property " +
                "`${ConfigConstants.ONLYTRAVERSEDIRECTORYOUTPUTS}'. Its " +
                "default value will change in Steep 6.0.0 from `false' to " +
                "`true'. Please specify the property in your configuration.")
          }
          readRecursive(it.variable.value, fs)
        }))
    }
  }

  /**
   * Try to cancel the process chain execution. Interrupt the thread that
   * executes the process chain.
   */
  fun cancel() {
    coroutineContext.cancel()
  }

  private suspend fun execute(exec: Executable, processChainId: String,
      executor: ExecutorService, vertx: VertxContextWrapper,
      progressUpdater: ((Double) -> Unit)? = null) {
    withTimeout(exec.maxInactivity, "maximum inactivity", exec.serviceId) {
      val collector = if (progressUpdater != null) {
        ProgressReportingOutputCollector(outputLinesToCollect, exec, progressUpdater)
      } else {
        DefaultOutputCollector(outputLinesToCollect)
      }.let {
        if (processChainLogsEnabled) {
          val logger = LoggerFactory.getLogger("${PROCESSCHAIN_LOG_PREFIX}${processChainId}")
          LoggingOutputCollector(it, logger)
        } else {
          it
        }
      }.let {
        if (exec.maxInactivity != null) {
          TimeoutOutputCollector(it, this)
        } else {
          it
        }
      }

      if (exec.runtime == Service.RUNTIME_DOCKER) {
        interruptable(executor) {
          withMDC(exec, processChainId) {
            dockerRuntime.execute(exec, collector)
          }
        }
      } else if (exec.runtime == Service.RUNTIME_OTHER) {
        interruptable(executor) {
          withMDC(exec, processChainId) {
            otherRuntime.execute(exec, collector)
          }
        }
      } else {
        val r = pluginRegistry.findRuntime(exec.runtime) ?:
            throw IllegalStateException("Unknown runtime: `${exec.runtime}'")
        if (r.compiledFunction.isSuspend) {
          withMDCSuspend(exec, processChainId) {
            r.compiledFunction.callSuspend(exec, collector, vertx)
          }
        } else {
          interruptable(executor) {
            withMDC(exec, processChainId) {
              r.compiledFunction.call(exec, collector, vertx)
            }
          }
        }
      }
    }
  }

  /**
   * Executes the given [block] with the given [executor]. Handles cancellation
   * requests and interrupts the thread that executes the [block].
   */
  private suspend fun <R> interruptable(executor: ExecutorService, block: () -> R): R {
    return suspendCancellableCoroutine { cont ->
      val f = executor.submit {
        try {
          cont.resume(block())
        } catch (ie: InterruptedException) {
          cont.resumeWithException(CancellationException(ie.message ?:
              "Process chain execution was interrupted"))
        } catch (t: Throwable) {
          cont.resumeWithException(t)
        }
      }

      cont.invokeOnCancellation {
        f.cancel(true)
      }
    }
  }

  /**
   * Populates the slf4j [MDC] with information about the [executable] and
   * the [processChainId] currently being executed
   */
  private fun populateMDC(executable: Executable, processChainId: String) {
    MDC.put("processChainId", processChainId)
    MDC.put("executableId", executable.id)
  }

  /**
   * Clears the slf4j [MDC] and removes the attributes added by [populateMDC]
   */
  private fun unpopulateMDC() {
    MDC.remove("executableId")
    MDC.remove("processChainId")
  }

  /**
   * Populates the slf4j [MDC] via [populateMDC], calls the given [block] and
   * then safely clears the [MDC] again
   */
  private fun withMDC(executable: Executable, processChainId: String, block: () -> Unit) {
    populateMDC(executable, processChainId)
    try {
      block()
    } finally {
      unpopulateMDC()
    }
  }

  /**
   * Populates the slf4j [MDC] via [populateMDC], calls the given [block] and
   * then safely clears the [MDC] again
   */
  private suspend fun withMDCSuspend(executable: Executable, processChainId: String,
      block: suspend () -> Unit) {
    populateMDC(executable, processChainId)
    try {
      block()
    } finally {
      unpopulateMDC()
    }
  }

  /**
   * Create `mkdir` commands for all output directories
   * @param outputs the outputs
   * @return the `mkdir` commands
   */
  private fun mkdirsForOutputs(outputs: List<Argument>): List<Executable> {
    val so = outputs.map {
      if (it.dataType == Argument.DATA_TYPE_DIRECTORY) {
        it.variable.value
      } else {
        File(it.variable.value).parent
      }
    }.filter { path ->
      if (mkdirCache.getIfPresent(path) == null) {
        mkdirCache.put(path, true)
        true
      } else {
        false
      }
    }

    return so.chunked(100).map { w ->
      Executable(
        path = "mkdir",
        arguments = listOf(
            Argument(
                label = "-p",
                variable = ArgumentVariable(UniqueID.next(), "true"),
                type = Argument.Type.INPUT,
                dataType = Argument.DATA_TYPE_BOOLEAN
            )
        ) + w.map { o ->
          Argument(
              variable = ArgumentVariable(UniqueID.next(), o),
              type = Argument.Type.INPUT
          )
        }
      )
    }
  }

  /**
   * Executes the given [block] with a timeout [policy] of a certain [type],
   * which has been defined for an executable with a given [serviceId]
   */
  private suspend fun <T> withTimeout(policy: TimeoutPolicy?, type: String,
      serviceId: String?, block: suspend TimeoutTimer.() -> T): T {
    // shortcut
    if (policy == null) {
      return block(NoopTimeoutTimer())
    }

    return coroutineScope {
      val timeout = DefaultTimeoutTimer(policy)
      var ex: TimeoutCancellationException? = null
      try {
        val job = async {
          block(timeout)
        }

        timeout.startTimeout {
          ex = TimeoutCancellationException(policy, type, serviceId)
          log.warn(ex!!.message)
          job.cancel(ex)
        }

        job.await()
      } catch (t: TimeoutCancellationException) {
        if (t === ex) {
          // if this is our exception (and not one from a child job), convert
          // it to an exception that is not derived from CancellationException
          // so we don't automatically cancel the parent job and can handle the
          // exception upstream
          throw TimeoutException(t.policy, t.type, t.serviceId)
        }
        // otherwise, just forward the exception - a child job may have timed out
        throw t
      } finally {
        timeout.cancelTimeout()
      }
    }
  }

  /**
   * An exception that will be thrown if the execution of a process chain was
   * cancelled due to a timeout
   * @param policy the timeout policy that led to the cancellation
   * @param type the type of the timeout policy
   * @param serviceId the ID of the service whose execution took too long
   */
  class TimeoutCancellationException(val policy: TimeoutPolicy, val type: String,
      val serviceId: String?) :
    CancellationException(makeTimeoutMessage(policy, type, serviceId))

  /**
   * An exception that will be thrown if the execution of a process chain was
   * aborted (with an error) due to a timeout
   * @param policy the timeout policy that led to the abortion
   * @param type the type of the timeout policy
   * @param serviceId the ID of the service whose execution took too long
   */
  class TimeoutException(val policy: TimeoutPolicy, val type: String,
      val serviceId: String?) :
    RuntimeException(makeTimeoutMessage(policy, type, serviceId))

  /**
   * A resettable timer that cancels the current job on timeout
   */
  private interface TimeoutTimer {
    /**
     * Reset the timer
     */
    fun resetTimeout()
  }

  /**
   * An implementation of [TimeoutTimer] that does no cancel the job
   */
  private class NoopTimeoutTimer : TimeoutTimer {
    override fun resetTimeout() {
      // nothing to do here
    }
  }

  /**
   * An implementation of [TimeoutTimer] that follows a given timeout [policy]
   * and runs a given block on timeout.
   */
  private inner class DefaultTimeoutTimer(private val policy: TimeoutPolicy) : TimeoutTimer {
    private var timerId: Long? = null
    private var block: ((Long) -> Unit)? = null

    override fun resetTimeout() {
      cancelTimeout()
      startTimeout()
    }

    private fun startTimeout() {
      if (block != null) {
        timerId = vertx.setTimer(policy.timeout, block)
      }
    }

    /**
     * Start timer and call the given [block] on timeout
     */
    fun startTimeout(block: (Long) -> Unit) {
      this.block = block
      startTimeout()
    }

    /**
     * Cancel the timer
     */
    fun cancelTimeout() {
      timerId?.let { vertx.cancelTimer(it) }
      timerId = null
    }
  }

  /**
   * An [OutputCollector] that resets the given [timeout] on every line received
   */
  private class TimeoutOutputCollector(private val delegate: OutputCollector,
      private val timeout: TimeoutTimer) : OutputCollector by delegate {
    override fun collect(line: String) {
      timeout.resetTimeout()
      delegate.collect(line)
    }
  }

  private inner class ProgressReportingOutputCollector(maxLines: Int,
      private val exec: Executable, private val progressUpdater: (Double) -> Unit) :
      DefaultOutputCollector(maxLines) {
    private val progressEstimator = exec.serviceId?.let {
      pluginRegistry.findProgressEstimator(it) }

    override fun collect(line: String) {
      super.collect(line)
      progressEstimator?.let { pe ->
        // make copy of lines because we are about to launch an asynchronous
        // task and need to avoid concurrent modification
        val linesCopy = lines()

        GlobalScope.launch(coroutineContext) {
          val progress = pe.call(exec, linesCopy, vertx)
          progress?.let { progressUpdater(progress) }
        }
      }
    }
  }

  /**
   * Small wrapper class that delegates to [Vertx] but holds a Vert.x context
   * that runtimes can access even if they are running in a different thread
   */
  private class VertxContextWrapper(delegate: Vertx,
      private val context: Context = delegate.orCreateContext) : Vertx by delegate {
    override fun getOrCreateContext(): Context = context
  }
}
