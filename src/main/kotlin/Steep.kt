import AddressConstants.CLUSTER_LIFECYCLE_MERGED
import AddressConstants.CLUSTER_NODE_LEFT
import AddressConstants.REMOTE_AGENT_ADDRESS_PREFIX
import AddressConstants.REMOTE_AGENT_BUSY
import AddressConstants.REMOTE_AGENT_IDLE
import AddressConstants.REMOTE_AGENT_PROCESSCHAINLOGS_SUFFIX
import agent.AgentRegistry.SelectCandidatesParam
import agent.LocalAgent
import agent.RemoteAgentRegistry
import com.fasterxml.jackson.module.kotlin.convertValue
import db.SubmissionRegistry
import helper.CompressedJsonObjectMessageCodec
import helper.JsonUtils
import helper.Shell
import helper.debounce
import helper.toDuration
import helper.withRetry
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.Message
import io.vertx.core.impl.ConcurrentHashSet
import io.vertx.core.impl.NoStackTraceThrowable
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.streams.ReadStream
import io.vertx.kotlin.core.eventbus.deliveryOptionsOf
import io.vertx.kotlin.core.file.openOptionsOf
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.toReceiveChannel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import model.processchain.ProcessChain
import model.retry.RetryPolicy
import org.slf4j.LoggerFactory
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlin.time.toKotlinDuration

/**
 * Steep's main API entry point
 * @author Michel Kraemer
 */
class Steep : CoroutineVerticle() {
  companion object {
    private val log = LoggerFactory.getLogger(Steep::class.java)
    private val ALL_LOCAL_AGENT_IDS = ConcurrentHashSet<String>()
  }

  private data class BusyMarker(val timestamp: Instant,
      val processChainId: String?, val replyAddresses: Set<String> = emptySet())

  private val startTime = Instant.now()
  private var stateChangedTime = startTime

  private lateinit var remoteAgentRegistry: RemoteAgentRegistry
  private lateinit var capabilities: Set<String>
  private var busy: BusyMarker? = null
  private val isExecuting = AtomicBoolean(false)
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
    busyTimeout = config.getString(
        ConfigConstants.AGENT_BUSY_TIMEOUT, "1m").toDuration()
    autoShutdownTimeout = config.getString(
        ConfigConstants.AGENT_AUTO_SHUTDOWN_TIMEOUT, "0m").toDuration()
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

    // safeguard to make sure we're always in the agent registry even if data
    // in the cluster is lost
    val reregister = debounce(vertx) {
      remoteAgentRegistry.register(agentId)
    }
    vertx.eventBus().localConsumer<String>(CLUSTER_NODE_LEFT) {
      reregister()
    }
    vertx.eventBus().localConsumer<Unit>(CLUSTER_LIFECYCLE_MERGED) {
      reregister()
    }

    // register consumer to provide process chain logs if we are the primary agent
    if (isPrimary) {
      vertx.eventBus().consumer(address + REMOTE_AGENT_PROCESSCHAINLOGS_SUFFIX,
          this::onProcessChainLogs)
    }

    ALL_LOCAL_AGENT_IDS.add(agentId)

    // setup automatic shutdown
    if (isPrimary && autoShutdownTimeout.toMillis() > 0) {
      val interval = if (autoShutdownTimeout.toMinutes() > 0) {
        1000L * 30L
      } else {
        5000L
      }
      vertx.setPeriodic(interval) {
        launch {
          checkAllAutoShutdown()
        }
      }
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
    ALL_LOCAL_AGENT_IDS.remove(agentId)
    remoteAgentRegistry.deregister(agentId)
  }

  /**
   * Check if all local agents have been idle for more than [autoShutdownTimeout]
   * and, if so, shut down the process.
   */
  private suspend fun checkAllAutoShutdown() {
    val allAgentsCanShutdown = ALL_LOCAL_AGENT_IDS.all { id ->
      if (id == agentId) {
        canAutoShutdown()
      } else {
        vertx.eventBus().request<Boolean>(REMOTE_AGENT_ADDRESS_PREFIX + id, jsonObjectOf(
            "action" to "canAutoShutdown"
        ), deliveryOptionsOf(localOnly = true)).await().body()
      }
    }
    if (allAgentsCanShutdown) {
      log.info("All local agents have been idle for more than " +
          "${autoShutdownTimeout.toKotlinDuration()}. Shutting down ...")

      // Use `exitProcess` instead of `vertx.close()` so all shutdown hooks
      // can be executed. Run it in a background thread to allow this verticle
      // to be undeployed.
      thread { exitProcess(0) }
    }
  }

  /**
   * Check if the agent has been idle for more than [autoShutdownTimeout]
   */
  private fun canAutoShutdown(): Boolean {
    return !isBusy() && lastExecuteTime.isBefore(Instant.now().minus(autoShutdownTimeout))
  }

  /**
   * Check if the agent has been idle for more than [autoShutdownTimeout]
   */
  private fun onCanAutoShutdown(msg: Message<JsonObject>) {
    msg.reply(canAutoShutdown())
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
        "canAutoShutdown" -> onCanAutoShutdown(msg)
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
        val props = fs.props(filepath).await()
        val f = fs.open(filepath, openOptionsOf(read = true,
            write = false, create = false)).await()
        (props.size() to f)
      } catch (t: Throwable) {
        if (t.cause is NoSuchFileException || t.cause?.cause is NoSuchFileException) {
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
      file.readLength = length

      try {
        // We were able to open the file. Respond immediately and let the
        // client know that we will send the file
        vertx.eventBus().request<Unit>(replyAddress, json {
          obj(
              "size" to size,
              "start" to start,
              "end" to (end - 1),
              "length" to length
          )
        }).await()

        if (!checkOnly) {
          // send the file to the reply address
          val channel = (file as ReadStream<Buffer>).toReceiveChannel(vertx)

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
            vertx.eventBus().request<Unit>(replyAddress, chunk, deliveryOptionsOf(
                codecName = CompressedJsonObjectMessageCodec.NAME
            )).await()
          }

          // send end marker
          vertx.eventBus().send(replyAddress, JsonObject())
        }
      } finally {
        file.close().await()
      }
    }
  }

  /**
   * Returns `true` if the agent is busy
   */
  private fun isBusy(): Boolean {
    if (isExecuting.get()) {
      // a process chain is currently being executed
      return true
    }
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
      // Prefer capability sets with the highest priority and the highest
      // number of process chains.
      val params = JsonUtils.mapper.convertValue<List<SelectCandidatesParam>>(
          msg.body().getJsonArray("params"))

      // calculate number of matching capabilities for each param and filter
      // out those that require capabilities we don't have
      val matching = params.mapIndexedNotNull { i, p ->
        var ok = true
        var n = 0
        for (rc in p.requiredCapabilities) {
          if (capabilities.contains(rc)) {
            n++
          } else {
            ok = false
            break
          }
        }
        if (!ok) {
          null
        } else {
          (i to p) to n
        }
      }

      // sort by number of matched capabilities, maxPriority, and number of process chains
      val sorted = matching.sortedWith(
          compareByDescending<Pair<Pair<Int, SelectCandidatesParam>, Int>> { it.second }
              .thenByDescending { it.first.second.maxPriority }
              .thenByDescending { it.first.second.count }
      )

      // get the index of the best one
      sorted.firstOrNull()?.first?.first ?: -1
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
        isExecuting.set(true)
        try {
          log.info("Executing process chain ${processChain.id} ...")
          val answer = executeProcessChain(processChain)

          // don't accept idempotent allocations anymore
          val replyAddresses = busy?.replyAddresses ?: listOf(replyAddress)
          markBusy(BusyMarker(Instant.now(), null))

          // send answer to all reply addresses (in parallel)
          replyAddresses.map { address ->
            async {
              withRetry(RetryPolicy(5, delay = 1000L, exponentialBackoff = 2)) {
                try {
                  log.info("Sending results of process chain ${processChain.id} " +
                      "to $address ...")
                  vertx.eventBus().request<Any>(address, answer, deliveryOptionsOf(
                      codecName = CompressedJsonObjectMessageCodec.NAME
                  )).await()
                } catch (t: Throwable) {
                  log.error("Error sending results of process chain " +
                      "${processChain.id} to $address")
                  throw t
                }
              }
            }
          }.awaitAll()
        } finally {
          lastExecuteTime = Instant.now()
          vertx.cancelTimer(busyTimer)
          isExecuting.set(false)
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
