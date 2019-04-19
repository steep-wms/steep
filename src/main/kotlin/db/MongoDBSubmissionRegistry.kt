package db

import db.SubmissionRegistry.ProcessChainStatus
import helper.JsonUtils
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.mongo.MongoClient
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.ext.mongo.FindOptions
import io.vertx.kotlin.ext.mongo.UpdateOptions
import io.vertx.kotlin.ext.mongo.countAwait
import io.vertx.kotlin.ext.mongo.findOneAndUpdateWithOptionsAwait
import io.vertx.kotlin.ext.mongo.findOneAwait
import io.vertx.kotlin.ext.mongo.findWithOptionsAwait
import io.vertx.kotlin.ext.mongo.insertAwait
import io.vertx.kotlin.ext.mongo.updateCollectionAwait
import model.Submission
import model.processchain.ProcessChain
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.format.DateTimeFormatter.ISO_INSTANT

/**
 * A submission registry that keeps objects in a MongoDB database
 * @param vertx the current Vert.x instance
 * @param connectionString the MongoDB connection string (e.g.
 * `mongodb://localhost:27017/database`)
 * @author Michel Kraemer
 */
class MongoDBSubmissionRegistry(private val vertx: Vertx,
    connectionString: String) : SubmissionRegistry {
  companion object {
    private val log = LoggerFactory.getLogger(MongoDBSubmissionRegistry::class.java)

    /**
     * Collection and property names
     */
    private const val COLL_SEQUENCE = "sequence"
    private const val COLL_SUBMISSIONS = "submissions"
    private const val COLL_PROCESS_CHAINS = "processChains"
    private const val INTERNAL_ID = "_id"
    private const val ID = "id"
    private const val SUBMISSION_ID = "submissionId"
    private const val START_TIME = "startTime"
    private const val END_TIME = "endTime"
    private const val STATUS = "status"
    private const val RESULTS = "results"
    private const val ERROR_MESSAGE = "errorMessage"
    private const val EXECUTION_STATE = "executionState"
    private const val SEQUENCE = "sequence"
    private const val VALUE = "value"

    /**
     * Fields to exclude when querying the `submissions` collection
     */
    private val SUBMISSION_EXCLUDES = json {
      obj(
          SEQUENCE to 0,
          EXECUTION_STATE to 0
      )
    }

    /**
     * Fields to exclude when querying the `processChains` collection
     */
    private val PROCESS_CHAIN_EXCLUDES = json {
      obj(
          SUBMISSION_ID to 0,
          STATUS to 0,
          START_TIME to 0,
          END_TIME to 0,
          RESULTS to 0,
          ERROR_MESSAGE to 0,
          SEQUENCE to 0
      )
    }
    private val PROCESS_CHAIN_EXCLUDES_BUT_SUBMISSION_ID =
        PROCESS_CHAIN_EXCLUDES.copy().also { it.remove(SUBMISSION_ID) }
  }

  private val client: MongoClient

  init {
    val config = json {
      obj(
          "connection_string" to connectionString
      )
    }
    client = MongoClient.createShared(vertx, config)

    // create indexes for `submission` collection
    client.runCommand("createIndexes", json {
      obj(
          "createIndexes" to COLL_SUBMISSIONS,
          "indexes" to array(
              obj(
                  "name" to "${STATUS}_",
                  "key" to obj(
                      STATUS to 1
                  ),
                  "background" to true
              )
          )
      )
    }) { ar ->
      if (ar.failed()) {
        log.error("Could not create indexes on collection `$COLL_SUBMISSIONS'", ar.cause())
      }
    }

    // create indexes for `processChains` collection
    client.runCommand("createIndexes", json {
      obj(
          "createIndexes" to COLL_PROCESS_CHAINS,
          "indexes" to array(
              obj(
                  "name" to "${SUBMISSION_ID}_",
                  "key" to obj(
                      SUBMISSION_ID to 1
                  ),
                  "background" to true
              ),
              obj(
                  "name" to "${STATUS}_",
                  "key" to obj(
                      STATUS to 1
                  ),
                  "background" to true
              )
          )
      )
    }) { ar ->
      if (ar.failed()) {
        log.error("Could not create indexes on collection `$COLL_PROCESS_CHAINS'", ar.cause())
      }
    }
  }

  override suspend fun close() {
    client.close()
  }

  /**
   * Get a next sequential number for a given [collection]
   */
  private suspend fun getNextSequence(collection: String): Long {
    val doc: JsonObject? = client.findOneAndUpdateWithOptionsAwait(COLL_SEQUENCE, json {
      obj(
          INTERNAL_ID to collection
      )
    }, json {
      obj(
          "\$inc" to obj(
            VALUE to 1
          )
      )
    }, FindOptions(), UpdateOptions(upsert = true))
    return doc?.getLong(VALUE, 0L) ?: 0L
  }

  override suspend fun addSubmission(submission: Submission) {
    val sequence = getNextSequence(COLL_SUBMISSIONS)
    val doc = JsonUtils.toJson(submission)
    doc.put(INTERNAL_ID, submission.id)
    doc.remove(ID)
    doc.put(SEQUENCE, sequence)
    client.insertAwait(COLL_SUBMISSIONS, doc)
  }

  /**
   * Deserialize a submission from a database [document]
   */
  private fun deserializeSubmission(document: JsonObject): Submission {
    document.remove(SEQUENCE)
    document.put(ID, document.getString(INTERNAL_ID))
    document.remove(INTERNAL_ID)
    document.remove(EXECUTION_STATE)
    return JsonUtils.fromJson(document)
  }

  override suspend fun findSubmissions(size: Int, offset: Int, order: Int):
      Collection<Submission> {
    val docs = client.findWithOptionsAwait(COLL_SUBMISSIONS, JsonObject(),
        FindOptions(limit = size, skip = offset, sort = json {
          obj(
              SEQUENCE to order
          )
        }, fields = SUBMISSION_EXCLUDES))
    return docs.map { deserializeSubmission(it) }
  }

  override suspend fun findSubmissionById(submissionId: String): Submission? {
    val doc: JsonObject? = client.findOneAwait(COLL_SUBMISSIONS, json {
      obj(
          INTERNAL_ID to submissionId
      )
    }, SUBMISSION_EXCLUDES)
    return doc?.let { deserializeSubmission(it) }
  }

  override suspend fun findSubmissionIdsByStatus(status: Submission.Status) =
      client.findWithOptionsAwait(COLL_SUBMISSIONS, json {
        obj(
            STATUS to status.toString()
        )
      }, FindOptions(fields = json {
        obj(
            INTERNAL_ID to 1
        )
      })).map { it.getString(INTERNAL_ID) }

  override suspend fun countSubmissions() =
      client.countAwait(COLL_SUBMISSIONS, JsonObject())

  /**
   * Set a [field] of a document with a given [id] in the given [collection] to
   * a specified [value]
   */
  private suspend fun updateField(collection: String, id: String, field: String, value: Any?) {
    client.updateCollectionAwait(collection, json {
      obj(
          INTERNAL_ID to id
      )
    }, json {
      obj(
          if (value != null) {
            "\$set" to obj(
                field to value
            )
          } else {
            "\$unset" to obj(
                field to ""
            )
          }
      )
    })
  }

  /**
   * Get the value of a [field] of a document with the given [id] and [type]
   * from the given [collection]
   */
  private suspend fun <T> getField(collection: String, type: String,
      id: String, field: String): T {
    val doc: JsonObject? = client.findOneAwait(collection, json {
      obj(
          INTERNAL_ID to id
      )
    }, json {
      obj(
          field to 1
      )
    })

    @Suppress
    if (doc == null) {
      throw NoSuchElementException("There is no $type with ID `$id'")
    }

    @Suppress("UNCHECKED_CAST")
    return doc.getValue(field) as T
  }

  private suspend fun <T> getSubmissionField(id: String, field: String): T =
      getField(COLL_SUBMISSIONS, "submission", id, field)

  private suspend fun <T> getProcessChainField(id: String, field: String): T =
      getField(COLL_PROCESS_CHAINS, "process chain", id, field)

  override suspend fun fetchNextSubmission(currentStatus: Submission.Status,
      newStatus: Submission.Status): Submission? {
    val doc: JsonObject? = client.findOneAndUpdateWithOptionsAwait(COLL_SUBMISSIONS, json {
      obj(
          STATUS to currentStatus.toString()
      )
    }, json {
      obj(
          "\$set" to obj(
              STATUS to newStatus.toString()
          )
      )
    }, FindOptions(fields = SUBMISSION_EXCLUDES), UpdateOptions())
    return doc?.let { deserializeSubmission(it) }
  }

  override suspend fun setSubmissionStartTime(submissionId: String, startTime: Instant) {
    updateField(COLL_SUBMISSIONS, submissionId, START_TIME, startTime)
  }

  override suspend fun setSubmissionEndTime(submissionId: String, endTime: Instant) {
    updateField(COLL_SUBMISSIONS, submissionId, END_TIME, endTime)
  }

  override suspend fun setSubmissionStatus(submissionId: String,
      status: Submission.Status) {
    updateField(COLL_SUBMISSIONS, submissionId, STATUS, status.toString())
  }

  override suspend fun getSubmissionStatus(submissionId: String) =
      getSubmissionField<String>(submissionId, STATUS).let {
        Submission.Status.valueOf(it)
      }

  override suspend fun setSubmissionExecutionState(submissionId: String, state: JsonObject?) {
    updateField(COLL_SUBMISSIONS, submissionId, EXECUTION_STATE, state)
  }

  override suspend fun getSubmissionExecutionState(submissionId: String): JsonObject? =
      getSubmissionField(submissionId, EXECUTION_STATE)

  override suspend fun addProcessChains(processChains: Collection<ProcessChain>,
      submissionId: String, status: ProcessChainStatus) {
    val submissionCount = client.countAwait(COLL_SUBMISSIONS, json {
      obj(
          INTERNAL_ID to submissionId
      )
    })
    if (submissionCount == 0L) {
      throw NoSuchElementException("There is no submission with ID `$submissionId'")
    }

    for (pc in processChains) {
      val sequence = getNextSequence(COLL_PROCESS_CHAINS)
      val doc = JsonUtils.toJson(pc)
      doc.put(INTERNAL_ID, pc.id)
      doc.remove(ID)
      doc.put(SEQUENCE, sequence)
      doc.put(SUBMISSION_ID, submissionId)
      doc.put(STATUS, status.toString())
      client.insertAwait(COLL_PROCESS_CHAINS, doc)
    }
  }

  /**
   * Deserialize a database [document] to a process chain
   */
  private fun deserializeProcessChain(document: JsonObject): Pair<ProcessChain, String> {
    document.remove(STATUS)
    val submissionId = (document.remove(SUBMISSION_ID) as String?) ?: ""
    document.remove(SEQUENCE)
    document.put(ID, document.getString(INTERNAL_ID))
    document.remove(INTERNAL_ID)
    return Pair(JsonUtils.fromJson(document), submissionId)
  }

  override suspend fun findProcessChains(size: Int, offset: Int, order: Int) =
      client.findWithOptionsAwait(COLL_PROCESS_CHAINS, JsonObject(),
          FindOptions(limit = size, skip = offset, sort = json {
            obj(
                SEQUENCE to order
            )
          }, fields = PROCESS_CHAIN_EXCLUDES_BUT_SUBMISSION_ID))
          .map { deserializeProcessChain(it) }

  override suspend fun findProcessChainsBySubmissionId(submissionId: String,
      size: Int, offset: Int, order: Int) =
      client.findWithOptionsAwait(COLL_PROCESS_CHAINS, json {
        obj(
            SUBMISSION_ID to submissionId
        )
      }, FindOptions(limit = size, skip = offset, sort = json {
        obj(
            SEQUENCE to order
        )
      }, fields = PROCESS_CHAIN_EXCLUDES))
          .map { deserializeProcessChain(it).first }

  override suspend fun findProcessChainStatusesBySubmissionId(submissionId: String) =
      client.findWithOptionsAwait(COLL_PROCESS_CHAINS, json {
        obj(
            SUBMISSION_ID to submissionId
        )
      }, FindOptions(sort = json {
        obj(
            SEQUENCE to 1
        )
      }, fields = json {
        obj(
            INTERNAL_ID to 1,
            STATUS to 1
        )
      })).associateBy({ it.getString(INTERNAL_ID) }, {
        ProcessChainStatus.valueOf(it.getString(STATUS)) })

  override suspend fun findProcessChainById(processChainId: String): ProcessChain? {
    val doc: JsonObject? = client.findOneAwait(COLL_PROCESS_CHAINS, json {
      obj(
          INTERNAL_ID to processChainId
      )
    }, PROCESS_CHAIN_EXCLUDES)
    return doc?.let { deserializeProcessChain(it).first }
  }

  override suspend fun countProcessChains() =
      client.countAwait(COLL_PROCESS_CHAINS, JsonObject())

  override suspend fun countProcessChainsBySubmissionId(submissionId: String) =
      client.countAwait(COLL_PROCESS_CHAINS, json {
        obj(
            SUBMISSION_ID to submissionId
        )
      })

  override suspend fun countProcessChainsByStatus(submissionId: String,
      status: ProcessChainStatus) =
      client.countAwait(COLL_PROCESS_CHAINS, json {
        obj(
            SUBMISSION_ID to submissionId,
            STATUS to status.toString()
        )
      })

  override suspend fun fetchNextProcessChain(currentStatus: ProcessChainStatus,
      newStatus: ProcessChainStatus): ProcessChain? {
    val doc: JsonObject? = client.findOneAndUpdateWithOptionsAwait(COLL_PROCESS_CHAINS, json {
      obj(
          STATUS to currentStatus.toString()
      )
    }, json {
      obj(
          "\$set" to obj(
              STATUS to newStatus.toString()
          )
      )
    }, FindOptions(fields = PROCESS_CHAIN_EXCLUDES), UpdateOptions())
    return doc?.let { deserializeProcessChain(it).first }
  }

  override suspend fun setProcessChainStartTime(processChainId: String, startTime: Instant?) {
    updateField(COLL_PROCESS_CHAINS, processChainId, START_TIME, startTime)
  }

  override suspend fun getProcessChainStartTime(processChainId: String): Instant? =
      getProcessChainField<String?>(processChainId, START_TIME)?.let {
        Instant.from(ISO_INSTANT.parse(it))
      }

  override suspend fun setProcessChainEndTime(processChainId: String, endTime: Instant?) {
    updateField(COLL_PROCESS_CHAINS, processChainId, END_TIME, endTime)
  }

  override suspend fun getProcessChainEndTime(processChainId: String): Instant? =
      getProcessChainField<String?>(processChainId, END_TIME)?.let {
        Instant.from(ISO_INSTANT.parse(it))
      }

  override suspend fun getProcessChainSubmissionId(processChainId: String): String =
      getProcessChainField(processChainId, SUBMISSION_ID)

  override suspend fun setProcessChainStatus(processChainId: String,
      status: ProcessChainStatus) {
    updateField(COLL_PROCESS_CHAINS, processChainId, STATUS, status.toString())
  }

  override suspend fun getProcessChainStatus(processChainId: String) =
      getProcessChainField<String>(processChainId, STATUS).let {
        ProcessChainStatus.valueOf(it)
      }

  override suspend fun setProcessChainResults(processChainId: String,
      results: Map<String, List<String>>?) {
    updateField(COLL_PROCESS_CHAINS, processChainId, RESULTS,
        results?.let{ JsonObject(it) })
  }

  override suspend fun getProcessChainResults(processChainId: String): Map<String, List<String>>? =
      getProcessChainField<JsonObject?>(processChainId, RESULTS)?.map?.mapValues { (_, v) ->
        (v as JsonArray).list.map { it as String } }

  override suspend fun setProcessChainErrorMessage(processChainId: String, errorMessage: String?) {
    updateField(COLL_PROCESS_CHAINS, processChainId, ERROR_MESSAGE, errorMessage)
  }

  override suspend fun getProcessChainErrorMessage(processChainId: String): String? =
      getProcessChainField(processChainId, ERROR_MESSAGE)
}
