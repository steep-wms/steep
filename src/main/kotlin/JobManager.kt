import agent.LocalAgent
import agent.RemoteAgentRegistry
import helper.JsonUtils
import helper.Shell
import io.vertx.core.eventbus.Message
import io.vertx.core.impl.NoStackTraceThrowable
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.CoroutineVerticle
import kotlinx.coroutines.launch
import model.processchain.ProcessChain
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * The JobManager's main API entry point
 * @author Michel Kraemer
 */
class JobManager : CoroutineVerticle() {
  companion object {
    private val log = LoggerFactory.getLogger(JobManager::class.java)
  }

  private val startTime = Instant.now()
  private var stateChangedTime = startTime

  private lateinit var capabilities: Set<String>
  private var busy: Instant? = null
  private lateinit var busyTimeout: Duration
  private var lastProcessChainSequence = -1L
  private lateinit var autoShutdownTimeout: Duration

  /**
   * The time when the last process chain finished executing
   */
  private var lastExecuteTime = Instant.now()

  override suspend fun start() {
    busyTimeout = Duration.ofSeconds(config.getLong(
        ConfigConstants.AGENT_BUSY_TIMEOUT, 60L))
    autoShutdownTimeout = Duration.ofMinutes(config.getLong(
        ConfigConstants.AGENT_AUTO_SHUTDOWN_TIMEOUT, 0))

    // consume process chains and run a local agent for each of them
    val address = RemoteAgentRegistry.AGENT_ADDRESS_PREFIX + Main.agentId
    vertx.eventBus().consumer<JsonObject>(address, this::onAgentMessage)

    // register remote agent
    capabilities = config.getJsonArray(ConfigConstants.AGENT_CAPABILTIIES,
        JsonArray()).map { it as String }.toSet()
    RemoteAgentRegistry(vertx).register(Main.agentId)

    // setup automatic shutdown
    if (autoShutdownTimeout.toMinutes() > 0) {
      vertx.setPeriodic(1000 * 30) { checkAutoShutdown() }
    }

    log.info("Remote agent `${Main.agentId}' successfully deployed")
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
      log.error("Idle agent `${Main.agentId}' was automatically marked as available again")
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
        vertx.eventBus().publish(AddressConstants.REMOTE_AGENT_BUSY,
            RemoteAgentRegistry.AGENT_ADDRESS_PREFIX + Main.agentId)
        stateChangedTime = Instant.now()
      }
      this.busy = Instant.now()
    } else {
      if (this.busy != null) {
        vertx.eventBus().publish(AddressConstants.REMOTE_AGENT_IDLE,
            RemoteAgentRegistry.AGENT_ADDRESS_PREFIX + Main.agentId)
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
          "id" to Main.agentId,
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
    val available = if (isBusy()) {
      false
    } else {
      // we are not busy - check if we have the required capabilities
      val arr = msg.body().getJsonArray("requiredCapabilities")
      val requiredCapabilities = arr.map { it as String }
      capabilities.containsAll(requiredCapabilities)
    }

    val reply = json {
      obj(
          "available" to available,
          "lastSequence" to lastProcessChainSequence
      )
    }
    msg.reply(reply)
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
          val answer = executeProcessChain(processChain)
          vertx.eventBus().send(replyAddress, answer)
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
    val la = LocalAgent(vertx)
    val results = la.execute(processChain)
    json {
      obj(
          "results" to JsonUtils.toJson(results)
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
      t.message
    }
    json {
      obj(
          "errorMessage" to message
      )
    }
  }
}
