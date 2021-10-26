import AddressConstants.REMOTE_AGENT_ADDRESS_PREFIX
import AddressConstants.REMOTE_AGENT_BUSY
import AddressConstants.REMOTE_AGENT_IDLE
import AddressConstants.REMOTE_AGENT_PROCESSCHAINLOGS_SUFFIX
import agent.LocalAgent
import agent.RemoteAgentRegistry
import db.SubmissionRegistry
import helper.JsonUtils
import helper.Shell
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.Message
import io.vertx.core.impl.NoStackTraceThrowable
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.streams.ReadStream
import io.vertx.kotlin.core.eventbus.requestAwait
import io.vertx.kotlin.core.file.closeAwait
import io.vertx.kotlin.core.file.openAwait
import io.vertx.kotlin.core.file.openOptionsOf
import io.vertx.kotlin.core.file.propsAwait
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.toChannel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import model.processchain.ProcessChain
import org.slf4j.LoggerFactory
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
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

  private data class BusyMarker(val timestamp: Instant,
      val processChainId: String?, val replyAddresses: Set<String> = emptySet())

  private val startTime = Instant.now()
  private var stateChangedTime = startTime

  private lateinit var remoteAgentRegistry: RemoteAgentRegistry
  private lateinit var capabilities: Set<String>
  private var busy: BusyMarker? = null
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
            "`${ConfigConstants.AGENT_ID}'")

    // check if we are the primary agent of this JVM
    val isPrimary = !agentId.matches(""".+\[\d+]""".toRegex())

    // consume process chains and run a local agent for each of them
    val address = REMOTE_AGENT_ADDRESS_PREFIX + agentId
    vertx.eventBus().consumer(address, this::onAgentMessage)

    // register remote agent
    capabilities = config.getJsonArray(ConfigConstants.AGENT_CAPABILTIIES,
        JsonArray()).map { it as String }.toSet()
    remoteAgentRegistry = RemoteAgentRegistry(vertx)
    remoteAgentRegistry.register(agentId)

    // register consumer to provide process chain logs if we are the primary agent
    if (isPrimary) {
      vertx.eventBus().consumer(address + REMOTE_AGENT_PROCESSCHAINLOGS_SUFFIX,
          this::onProcessChainLogs)
    }

    // setup automatic shutdown
    if (autoShutdownTimeout.toMinutes() > 0) {
      vertx.setPeriodic(1000L * 30L) { checkAutoShutdown() }
    }

    if (capabilities.isEmpty()) {
      log.info("Remote agent `${agentId}' successfully deployed")
    } else {
      log.info("Remote agent `${agentId}' with capabilities " +
          "`$capabilities' successfully deployed")
    }
  }

  override suspend fun stop() {
    log.info("Stopping remote agent $agentId ...")
    remoteAgentRegistry.deregister(agentId)
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
   * Handle message to get process chain logs
   */
  private fun onProcessChainLogs(msg: Message<JsonObject>) {
    try {
      val jsonObj: JsonObject = msg.body()
      when (val action = jsonObj.getString("action")) {
        "exists" -> onFetchProcessChainLogs(msg, true)
        "fetch" -> onFetchProcessChainLogs(msg)
        else -> throw NoStackTraceThrowable("Unknown action `$action'")
      }
    } catch (e: Throwable) {
      msg.fail(400, e.message)
    }
  }

  /**
   * Fetch a certain process chain log. Set [checkOnly] to `true` if the method
   * should only check if the log file exists but not stream back its contents.
   */
  private fun onFetchProcessChainLogs(msg: Message<JsonObject>, checkOnly: Boolean = false) {
    val jsonObj: JsonObject = msg.body()

    // get address where we need to send the data to
    val replyAddress = jsonObj.getString("replyAddress")
    if (replyAddress == null) {
      // there's nothing we can do here
      log.error("No reply address given")
      return
    }

    // get process chain ID
    val id = jsonObj.getString("id")
    if (id == null) {
      vertx.eventBus().send(replyAddress, json {
        obj(
            "error" to 400,
            "message" to "Missing process chain ID"
        )
      })
      return
    }

    // create log file path
    val path = config.getString(ConfigConstants.LOGS_PROCESSCHAINS_PATH)
    if (path == null) {
      vertx.eventBus().send(replyAddress, json {
        obj(
            "error" to 404,
            "message" to "Path to process chain logs not configured"
        )
      })
      return
    }

    val groupByPrefix = config.getInteger(ConfigConstants.LOGS_PROCESSCHAINS_GROUPBYPREFIX, 0)

    val filename = "$id.log"
    val filepath = if (groupByPrefix > 0) {
      val prefix = id.substring(0, groupByPrefix)
      Paths.get(path, prefix, filename).toString()
    } else {
      Paths.get(path, filename).toString()
    }

    launch {
      // try to open the log file
      val (size, file) = try {
        val fs = vertx.fileSystem()
        val props = fs.propsAwait(filepath)
        val f = fs.openAwait(filepath, openOptionsOf(read = true,
            write = false, create = false))
        (props.size() to f)
      } catch (t: Throwable) {
        if (t.cause is NoSuchFileException) {
          vertx.eventBus().send(replyAddress, json {
            obj(
                "error" to 404,
                "message" to "Log file does not exist"
            )
          })
        } else {
          log.error("Could not open process chain log file `$filepath'", t)
          vertx.eventBus().send(replyAddress, json {
            obj(
                "error" to 500,
                "message" to "Could not open process chain log file"
            )
          })
        }
        return@launch
      }

      val givenStart = jsonObj.getLong("start", 0)
      val (start, end) = if (givenStart >= 0) {
        if (givenStart >= size) {
          vertx.eventBus().send(replyAddress, json {
            obj(
                "error" to 416,
                "message" to "Range start position out of bounds"
            )
          })
          return@launch
        }

        val givenEnd = jsonObj.getLong("end")
        val e = when {
          givenEnd == null -> size
          givenEnd < givenStart -> {
            vertx.eventBus().send(replyAddress, json {
              obj(
                  "error" to 416,
                  "message" to "Range end position must not be less than " +
                      "range start position"
              )
            })
            return@launch
          }
          else -> (givenEnd + 1).coerceAtMost(size)
        }

        (givenStart to e)
      } else {
        ((size + givenStart).coerceAtLeast(0) to size)
      }
      file.setReadPos(start)
      val length = end - start
      file.setReadLength(length)

      try {
        // We were able to open the file. Respond immediately and let the
        // client know that we will send the file
        vertx.eventBus().requestAwait<Unit>(replyAddress, json {
          obj(
              "size" to size,
              "start" to start,
              "end" to (end - 1),
              "length" to length
          )
        })

        if (!checkOnly) {
          // send the file to the reply address
          val channel = (file as ReadStream<Buffer>).toChannel(vertx)

          file.exceptionHandler { t ->
            vertx.eventBus().send(replyAddress, json {
              obj(
                  "error" to 500,
                  "message" to t.message
              )
            })
            channel.cancel()
          }

          for (buf in channel) {
            val chunk = json {
              obj(
                  "data" to buf.toString()
              )
            }
            vertx.eventBus().requestAwait<Unit>(replyAddress, chunk)
          }

          // send end marker
          vertx.eventBus().send(replyAddress, JsonObject())
        }
      } finally {
        file.closeAwait()
      }
    }
  }

  /**
   * Returns `true` if the agent is busy
   */
  private fun isBusy(): Boolean {
    val timedOut = busy?.timestamp?.isBefore(Instant.now().minus(busyTimeout)) ?: return false
    if (timedOut) {
      markBusy(null)
      log.error("Idle agent `${agentId}' was automatically marked as available again")
      return false
    }
    return true
  }

  /**
   * Marks this agent as busy or not busy
   */
  private fun markBusy(marker: BusyMarker?) {
    if (marker != null) {
      if (this.busy == null) {
        stateChangedTime = Instant.now()
        val msg = json {
          obj(
            "id" to agentId,
            "available" to false,
            "stateChangedTime" to stateChangedTime
          )
        }
        if (marker.processChainId != null) {
          msg.put("processChainId", marker.processChainId)
        }
        vertx.eventBus().publish(REMOTE_AGENT_BUSY, msg)
      }
      this.busy = marker
    } else {
      if (this.busy != null) {
        stateChangedTime = Instant.now()
        val msg = json {
          obj(
              "id" to agentId,
              "available" to true,
              "stateChangedTime" to stateChangedTime
          )
        }
        vertx.eventBus().publish(REMOTE_AGENT_IDLE, msg)
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

    val processChainId = busy?.processChainId
    if (processChainId != null) {
      reply.put("processChainId", processChainId)
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

    if (msg.body().getBoolean("includeCapabilities", false)) {
      val capsArr = JsonArray()
      capabilities.forEach { capsArr.add(it) }
      reply.put("capabilities", capsArr)
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
   * Handle an allocation message. This method is idempotent if the
   * `processChainId` in the message equals the ID of the process chain
   * currently being executed.
   */
  private fun onAgentAllocate(msg: Message<JsonObject>) {
    val processChainId = msg.body().getString("processChainId")
    if (isBusy() && busy?.processChainId != processChainId) {
      msg.fail(503, "Agent is busy")
    } else {
      val currentBusy = busy
      if (currentBusy?.processChainId != null && currentBusy.processChainId == processChainId) {
        markBusy(currentBusy.copy(timestamp = Instant.now()))
      } else {
        markBusy(BusyMarker(Instant.now(), processChainId))
      }
      msg.reply("ACK")
    }
  }

  /**
   * Handle a deallocation message
   */
  private fun onAgentDeallocate(msg: Message<JsonObject>) {
    markBusy(null)
    msg.reply("ACK")
  }

  /**
   * Extract the process chain and a reply address from the given message and
   * send an acknowledgement. Execute the process chain asynchronously with a
   * [LocalAgent] and send the results to the reply address. This method is
   * idempotent if the same process chain is already being executed.
   * @param msg the message containing the process chain and the reply address
   */
  private fun onProcessChain(msg: Message<JsonObject>) {
    try {
      // parse message
      val jsonObj: JsonObject = msg.body()
      val replyAddress: String = jsonObj["replyAddress"]
      val processChain = JsonUtils.fromJson<ProcessChain>(jsonObj["processChain"])
      lastProcessChainSequence = jsonObj.getLong("sequence", -1L)

      val currentBusy = busy
      if (currentBusy != null) {
        val updatedMarker = currentBusy.copy(timestamp = Instant.now(),
            processChainId = processChain.id,
            replyAddresses = currentBusy.replyAddresses + replyAddress)
        markBusy(updatedMarker)

        if (currentBusy.processChainId == processChain.id && currentBusy.replyAddresses.isNotEmpty()) {
          // We are already executing this process chain. Return now!
          log.info("Agent `$agentId' has been allocated with the same process " +
              "chain `${processChain.id}'. Continuing execution.")
          msg.reply("ACK")
          return
        }
      } else {
        val marker = BusyMarker(Instant.now(), processChain.id, setOf(replyAddress))
        markBusy(marker)
      }

      val busyTimer = vertx.setPeriodic(busyTimeout.toMillis() / 2) {
        // periodically update timestamp of current busy marker
        val currentMarker = busy // get most current marker
        if (currentMarker != null) {
          markBusy(currentMarker.copy(timestamp = Instant.now()))
        }
      }

      // run the local agent and return its results
      launch {
        try {
          log.info("Executing process chain ${processChain.id} ...")
          val answer = executeProcessChain(processChain)

          // don't accept idempotent allocations anymore
          val replyAddresses = busy?.replyAddresses ?: listOf(replyAddress)
          markBusy(BusyMarker(Instant.now(), null))

          // send answer to all reply addresses
          for (address in replyAddresses) {
            for (tries in 4 downTo 0) {
              try {
                log.info("Sending results of process chain ${processChain.id} " +
                    "to $address ...")
                vertx.eventBus().requestAwait<Any>(address, answer)
                break
              } catch (t: Throwable) {
                log.error("Error sending results of process chain " +
                    "${processChain.id} to peer. Waiting 1 second. " +
                    "$tries retries remaining.", t)
                delay(1000)
              }
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
