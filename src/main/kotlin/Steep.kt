import AddressConstants.REMOTE_AGENT_ADDRESS_PREFIX
import AddressConstants.REMOTE_AGENT_BUSY
import AddressConstants.REMOTE_AGENT_IDLE
import agent.LocalAgent
import agent.RemoteAgentRegistry
import db.SubmissionRegistry
import helper.JsonUtils
import helper.Shell
import io.vertx.core.eventbus.Message
import io.vertx.core.impl.NoStackTraceThrowable
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.eventbus.requestAwait
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.CoroutineVerticle
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import model.processchain.ProcessChain
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

/**
 * Steep's main API entry point
 * @author Michel Kraemer
 */
class Steep : CoroutineVerticle() {
  companion object {
    private val log = LoggerFactory.getLogger(Steep::class.java)
  }

  private val startTime = Instant.now()
  private var stateChangedTime = startTime

  private lateinit var capabilities: Set<String>
  private var busy: Instant? = null
  private lateinit var busyTimeout: Duration
  private var lastProcessChainSequence = -1L
  private lateinit var autoShutdownTimeout: Duration
  private lateinit var agentId: String

  /**
   * An executor service that can be passed to a [LocalAgent] to run process
   * chains in a background thread
   */
  private val executorService = Executors.newCachedThreadPool()
  private val localAgentDispatcher = executorService.asCoroutineDispatcher()

  /**
   * The time when the last process chain finished executing (or when a
   * keep-alive message has been received)
   */
  private var lastExecuteTime = Instant.now()

  override suspend fun start() {
    busyTimeout = Duration.ofSeconds(config.getLong(
        ConfigConstants.AGENT_BUSY_TIMEOUT, 60L))
    autoShutdownTimeout = Duration.ofMinutes(config.getLong(
        ConfigConstants.AGENT_AUTO_SHUTDOWN_TIMEOUT, 0))
    agentId = config.getString(ConfigConstants.AGENT_ID) ?:
        throw IllegalStateException("Missing configuration item " +
            "`${ConfigConstants.AGENT_AUTO_SHUTDOWN_TIMEOUT}'")

    // consume process chains and run a local agent for each of them
    val address = REMOTE_AGENT_ADDRESS_PREFIX + agentId
    vertx.eventBus().consumer(address, this::onAgentMessage)

    // register remote agent
    capabilities = config.getJsonArray(ConfigConstants.AGENT_CAPABILTIIES,
        JsonArray()).map { it as String }.toSet()
    RemoteAgentRegistry(vertx).register(agentId)

    // setup automatic shutdown
    if (autoShutdownTimeout.toMinutes() > 0) {
      vertx.setPeriodic(1000 * 30) { checkAutoShutdown() }
    }

    if (capabilities.isEmpty()) {
      log.info("Remote agent `${agentId}' successfully deployed")
    } else {
      log.info("Remote agent `${agentId}' with capabilities " +
          "`$capabilities' successfully deployed")
    }
  }

  /**
   * Checks if the agent has been idle for more than [autoShutdownTimeout]
   * minutes and, if so, shuts down the Vert.x instance.
   */
  private fun checkAutoShutdown() {
    if (!isBusy() && lastExecuteTime.isBefore(Instant.now().minus(autoShutdownTimeout))) {
      log.info("Agent has been idle for more than ${autoShutdownTimeout.toMinutes()} " +
          "minutes. Shutting down ...")
      vertx.close()
    }
  }

  /**
   * Handle a message that is sent to our agent
   */
  private fun onAgentMessage(msg: Message<JsonObject>) {
    try {
      val jsonObj: JsonObject = msg.body()
      when (val action = jsonObj.getString("action")) {
        "info" -> onAgentInfo(msg)
        "inquire" -> onAgentInquire(msg)
        "keepAlive" -> onAgentKeepAlive()
        "allocate" -> onAgentAllocate(msg)
        "deallocate" -> onAgentDeallocate(msg)
        "process" -> onProcessChain(msg)
        else -> throw NoStackTraceThrowable("Unknown action `$action'")
      }
    } catch (e: Throwable) {
      msg.fail(400, e.message)
    }
  }

  /**
   * Returns `true` if the agent is busy
   */
  private fun isBusy(): Boolean {
    val timedOut = busy?.isBefore(Instant.now().minus(busyTimeout)) ?: return false
    if (timedOut) {
      markBusy(false)
      log.error("Idle agent `${agentId}' was automatically marked as available again")
      return false
    }
    return true
  }

  /**
   * Marks this agent as busy or not busy
   */
  private fun markBusy(busy: Boolean = true) {
    if (busy) {
      if (this.busy == null) {
        vertx.eventBus().publish(REMOTE_AGENT_BUSY,
            REMOTE_AGENT_ADDRESS_PREFIX + agentId)
        stateChangedTime = Instant.now()
      }
      this.busy = Instant.now()
    } else {
      if (this.busy != null) {
        vertx.eventBus().publish(REMOTE_AGENT_IDLE,
            REMOTE_AGENT_ADDRESS_PREFIX + agentId)
        stateChangedTime = Instant.now()
      }
      this.busy = null
    }
  }

  /**
   * Return information about this agent
   */
  private fun onAgentInfo(msg: Message<JsonObject>) {
    val reply = json {
      obj(
          "id" to agentId,
          "available" to !isBusy(),
          "capabilities" to array(*capabilities.toTypedArray()),
          "startTime" to startTime,
          "stateChangedTime" to stateChangedTime
      )
    }
    msg.reply(reply)
  }

  /**
   * Handle an inquiry whether we are able to handle a process chain that
   * requires a given set of capabilities
   */
  private fun onAgentInquire(msg: Message<JsonObject>) {
    val bestRequiredCapabilities = if (isBusy()) {
      -1
    } else {
      // Select requiredCapabilities that best match our own capabilities.
      // Prefer capability sets with a high number of process chains.
      val arr = msg.body().getJsonArray("requiredCapabilities")
      var max = -1L
      var best = -1

      for ((i, rcs) in arr.withIndex()) {
        val rcsObj = rcs as JsonObject
        val rcsArr = rcsObj.getJsonArray("capabilities")
        val rcsCount = rcsObj.getLong("processChainCount")
        var ok = true
        var count = 0L

        for (rc in rcsArr) {
          if (capabilities.contains(rc)) {
            count += rcsCount
          } else {
            ok = false
            break
          }
        }

        if (ok && count > max) {
          max = count
          best = i
        }
      }

      best
    }

    val reply = json {
      if (bestRequiredCapabilities != -1) {
        obj(
            "available" to true,
            "bestRequiredCapabilities" to bestRequiredCapabilities,
            "lastSequence" to lastProcessChainSequence
        )
      } else {
        obj(
            "available" to false,
            "lastSequence" to lastProcessChainSequence
        )
      }
    }
    msg.reply(reply)
  }

  /**
   * Handle a keep-alive message
   */
  private fun onAgentKeepAlive() {
    // pretend we executed something so we don't shut down ourselves
    // see checkAutoShutdown()
    lastExecuteTime = Instant.now()
  }

  /**
   * Handle an allocation message
   */
  private fun onAgentAllocate(msg: Message<JsonObject>) {
    if (isBusy()) {
      msg.fail(503, "Agent is busy")
    } else {
      markBusy()
      msg.reply("ACK")
    }
  }

  /**
   * Handle a deallocation message
   */
  private fun onAgentDeallocate(msg: Message<JsonObject>) {
    markBusy(false)
    msg.reply("ACK")
  }

  /**
   * Extract the process chain and a reply address from the given message and
   * send an acknowledgement. Execute the process chain asynchronously with a
   * [LocalAgent] and send the results to the reply address
   * @param msg the message containing the process chain and the reply address
   */
  private fun onProcessChain(msg: Message<JsonObject>) {
    try {
      // parse message
      val jsonObj: JsonObject = msg.body()
      val replyAddress: String = jsonObj["replyAddress"]
      val processChain = JsonUtils.fromJson<ProcessChain>(jsonObj["processChain"])
      lastProcessChainSequence = jsonObj.getLong("sequence", -1L)

      markBusy()
      val busyTimer = vertx.setPeriodic(busyTimeout.toMillis() / 2) {
        markBusy()
      }

      // run the local agent and return its results
      launch {
        try {
          log.info("Executing process chain ${processChain.id} ...")
          val answer = executeProcessChain(processChain)
          for (tries in 4 downTo 0) {
            try {
              log.info("Sending results of process chain ${processChain.id} " +
                  "to $replyAddress ...")
              vertx.eventBus().requestAwait<Any>(replyAddress, answer)
              break
            } catch (t: Throwable) {
              log.error("Error sending results of process chain " +
                  "${processChain.id} to peer. Waiting 1 second. " +
                  "$tries retries remaining.", t)
              delay(1000)
            }
          }
        } finally {
          lastExecuteTime = Instant.now()
          vertx.cancelTimer(busyTimer)
        }
      }

      // send acknowledgement
      msg.reply("ACK")
    } catch (e: Throwable) {
      msg.fail(400, e.message)
    }
  }

  /**
   * Execute the given process chain with a [LocalAgent] and return an object
   * that can be sent back to the remote peer
   * @param processChain the process chain to execute
   * @return the reply message (containing either results or an error message)
   */
  private suspend fun executeProcessChain(processChain: ProcessChain) = try {
    val la = LocalAgent(vertx, localAgentDispatcher)
    val results = la.execute(processChain)
    json {
      obj(
          "results" to JsonUtils.toJson(results),
          "status" to SubmissionRegistry.ProcessChainStatus.SUCCESS.toString()
      )
    }
  } catch (_: CancellationException) {
    log.debug("Process chain execution was cancelled")
    json {
      obj(
          "status" to SubmissionRegistry.ProcessChainStatus.CANCELLED.toString()
      )
    }
  } catch (t: Throwable) {
    val message = if (t is Shell.ExecutionException) {
      """
        ${t.message}

        Exit code: ${t.exitCode}

        ${t.lastOutput}
      """.trimIndent()
    } else {
      log.debug("Could not execute process chain", t)
      t.message ?: "Unknown internal error"
    }
    json {
      obj(
          "errorMessage" to message,
          "status" to SubmissionRegistry.ProcessChainStatus.ERROR.toString()
      )
    }
  }
}
