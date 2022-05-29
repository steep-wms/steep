package db

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.module.kotlin.readValue
import com.mongodb.MongoGridFSException
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.IndexModel
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.InsertOneModel
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.gridfs.GridFSBucket
import com.mongodb.reactivestreams.client.gridfs.GridFSBuckets
import db.SubmissionRegistry.ProcessChainStatus
import helper.DefaultSubscriber
import helper.JsonUtils
import helper.UniqueID
import helper.aggregateAwait
import helper.bulkWriteAwait
import helper.countDocumentsAwait
import helper.deleteAwait
import helper.deleteManyAwait
import helper.distinctAwait
import helper.download
import helper.findAwait
import helper.findOneAndUpdateAwait
import helper.findOneAwait
import helper.insertOneAwait
import helper.updateManyAwait
import helper.updateOneAwait
import helper.upload
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.jsonArrayOf
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.core.json.obj
import model.Submission
import model.processchain.Executable
import model.processchain.ProcessChain
import org.slf4j.LoggerFactory
import search.Query
import search.SearchResult
import search.Type
import java.nio.ByteBuffer
import java.time.Instant
import java.time.format.DateTimeFormatter.ISO_INSTANT
import io.vertx.ext.mongo.impl.JsonObjectBsonAdapter as wrap

/**
 * A submission registry that keeps objects in a MongoDB database
 * @param vertx the current Vert.x instance
 * @param connectionString the MongoDB connection string (e.g.
 * `mongodb://localhost:27017/database`)
 * @param createIndexes `true` if indexes should be created
 * @author Michel Kraemer
 */
class MongoDBSubmissionRegistry(private val vertx: Vertx,
    connectionString: String, createIndexes: Boolean = true) :
    MongoDBRegistry(connectionString), SubmissionRegistry {
  companion object {
    private val log = LoggerFactory.getLogger(MongoDBSubmissionRegistry::class.java)

    /**
     * Collection and property names
     */
    private const val COLL_SUBMISSIONS = "submissions"
    private const val COLL_PROCESS_CHAINS = "processChains"
    private const val BUCKET_PROCESS_CHAINS = "processChains"
    private const val BUCKET_PROCESS_CHAIN_RESULTS = "processChainResults"
    private const val BUCKET_EXECUTION_STATES = "executionStates"
    private const val BUCKET_SUBMISSION_RESULTS = "submissionResults"
    private const val SUBMISSION_ID = "submissionId"
    private const val START_TIME = "startTime"
    private const val END_TIME = "endTime"
    private const val STATUS = "status"
    private const val REQUIRED_CAPABILITIES = "requiredCapabilities"
    private const val ERROR_MESSAGE = "errorMessage"
    private const val SEQUENCE = "sequence"
    private const val PRIORITY = "priority"
    private const val WORKFLOW = "workflow"
    private const val SOURCE = "source"

    /**
     * Fields to exclude when querying the `submissions` collection
     */
    private val SUBMISSION_EXCLUDES = json {
      obj(
          ERROR_MESSAGE to 0,
          SEQUENCE to 0
      )
    }
    private val SUBMISSION_EXCLUDES_WITH_WORKFLOW = SUBMISSION_EXCLUDES.copy()
        .put(WORKFLOW, 0)
    private val SUBMISSION_EXCLUDES_WITH_SOURCE = SUBMISSION_EXCLUDES.copy()
        .put(SOURCE, 0)
    private val SUBMISSION_EXCLUDES_WITH_WORKFLOW_AND_SOURCE = SUBMISSION_EXCLUDES.copy()
        .put(WORKFLOW, 0)
        .put(SOURCE, 0)

    /**
     * Fields to exclude when querying the `processChains` collection
     */
    private val PROCESS_CHAIN_EXCLUDES = json {
      obj(
          SUBMISSION_ID to 0,
          STATUS to 0,
          START_TIME to 0,
          END_TIME to 0,
          ERROR_MESSAGE to 0,
          SEQUENCE to 0,
          REQUIRED_CAPABILITIES to 0
      )
    }
    private val PROCESS_CHAIN_EXCLUDES_BUT_SUBMISSION_ID =
        PROCESS_CHAIN_EXCLUDES.copy().also { it.remove(SUBMISSION_ID) }

    /**
     * A Jackson mix-in to ignore process chain executables during deserialization
     */
    abstract class ProcessChainIgnoreExecutablesMixin {
      @JsonIgnore
      val executables: List<Executable> = emptyList()
    }
    private val ignoreExecutablesMapper = JsonUtils.mapper.copy().addMixIn(
        ProcessChain::class.java, ProcessChainIgnoreExecutablesMixin::class.java)
  }

  private val collSubmissions: MongoCollection<JsonObject> =
      db.getCollection(COLL_SUBMISSIONS, JsonObject::class.java)
  private val collProcessChains: MongoCollection<JsonObject> =
      db.getCollection(COLL_PROCESS_CHAINS, JsonObject::class.java)
  private val bucketProcessChains: GridFSBucket =
      GridFSBuckets.create(db, BUCKET_PROCESS_CHAINS)
  private val bucketProcessChainResults: GridFSBucket =
      GridFSBuckets.create(db, BUCKET_PROCESS_CHAIN_RESULTS)
  private val bucketExecutionStates: GridFSBucket =
      GridFSBuckets.create(db, BUCKET_EXECUTION_STATES)
  private val bucketSubmissionResults: GridFSBucket =
      GridFSBuckets.create(db, BUCKET_SUBMISSION_RESULTS)

  init {
    if (createIndexes) {
      // create indexes for `submission` collection
      collSubmissions.createIndexes(listOf(
          IndexModel(Indexes.ascending(STATUS), IndexOptions().background(true)),
          IndexModel(Indexes.ascending(SEQUENCE), IndexOptions().background(true)),
          IndexModel(Indexes.descending("$WORKFLOW.$PRIORITY"), IndexOptions().background(true))
      )).subscribe(object : DefaultSubscriber<String>() {
        override fun onError(t: Throwable) {
          log.error("Could not create index on collection `$COLL_SUBMISSIONS'", t)
        }
      })

      // create indexes for `processChains` collection
      collProcessChains.createIndexes(listOf(
          IndexModel(Indexes.ascending(SUBMISSION_ID), IndexOptions().background(true)),
          IndexModel(Indexes.ascending(STATUS), IndexOptions().background(true)),
          IndexModel(Indexes.ascending(PRIORITY), IndexOptions().background(true)),
          IndexModel(Indexes.ascending(SEQUENCE), IndexOptions().background(true)),
          IndexModel(Indexes.ascending(REQUIRED_CAPABILITIES), IndexOptions().background(true)),
          // compound index to speed up fetchNextProcessChain
          IndexModel(Indexes.ascending(STATUS, REQUIRED_CAPABILITIES, PRIORITY, SEQUENCE), IndexOptions().background(true))
      )).subscribe(object : DefaultSubscriber<String>() {
        override fun onError(t: Throwable) {
          log.error("Could not create index on collection `$COLL_PROCESS_CHAINS'", t)
        }
      })
    }
  }

  override suspend fun addSubmission(submission: Submission) {
    val sequence = getNextSequence(COLL_SUBMISSIONS)
    val doc = JsonUtils.toJson(submission)
    doc.put(INTERNAL_ID, submission.id)
    doc.remove(ID)
    doc.put(SEQUENCE, sequence)

    // Make sure there's always a priority even if it's 0 (we configured Jackson
    // to not serialize 0's by default). Otherwise, we can't sort correctly.
    doc.getJsonObject(WORKFLOW)?.put(PRIORITY, submission.workflow.priority)

    collSubmissions.insertOneAwait(doc)
  }

  private fun cleanSubmissionDocument(document: JsonObject) {
    document.remove(ERROR_MESSAGE)
    document.remove(SEQUENCE)
    document.put(ID, document.getString(INTERNAL_ID))
    document.remove(INTERNAL_ID)

    // remove priority that we only added for sorting (see [addSubmission])
    if (document.getJsonObject(WORKFLOW)?.getInteger(PRIORITY) == 0) {
      document.getJsonObject(WORKFLOW)?.remove(PRIORITY)
    }
  }

  /**
   * Deserialize a submission from a database [document]
   */
  private fun deserializeSubmission(document: JsonObject): Submission {
    cleanSubmissionDocument(document)
    return JsonUtils.fromJson(document)
  }

  override suspend fun findSubmissionsRaw(status: Submission.Status?, size: Int,
      offset: Int, order: Int, excludeWorkflows: Boolean,
      excludeSources: Boolean): Collection<JsonObject> {
    val excludes = if (excludeWorkflows && excludeSources) {
      SUBMISSION_EXCLUDES_WITH_WORKFLOW_AND_SOURCE
    } else if (excludeWorkflows) {
      SUBMISSION_EXCLUDES_WITH_WORKFLOW
    } else if (excludeSources) {
      SUBMISSION_EXCLUDES_WITH_SOURCE
    } else {
      SUBMISSION_EXCLUDES
    }
    val docs = collSubmissions.findAwait(JsonObject().also {
      if (status != null) {
        it.put(STATUS, status.toString())
      }
    }, size, offset, json {
      obj(
          SEQUENCE to order
      )
    }, excludes)
    docs.forEach { cleanSubmissionDocument(it) }
    return docs
  }

  override suspend fun findSubmissionById(submissionId: String): Submission? {
    val doc = collSubmissions.findOneAwait(json {
      obj(
          INTERNAL_ID to submissionId
      )
    }, SUBMISSION_EXCLUDES)
    return doc?.let { deserializeSubmission(it) }
  }

  override suspend fun findSubmissionIdsByStatus(status: Submission.Status) =
      collSubmissions.findAwait(json {
        obj(
            STATUS to status.toString()
        )
      }, projection = json {
        obj(
            INTERNAL_ID to 1
        )
      }).map { it.getString(INTERNAL_ID) }

  override suspend fun countSubmissions(status: Submission.Status?) =
      collSubmissions.countDocumentsAwait(JsonObject().also {
        if (status != null) {
          it.put(STATUS, status.toString())
        }
      })

  /**
   * Get the value of a [field] of a document with the given [id] and [type]
   * from the given [collection]
   */
  private suspend inline fun <reified T> getField(collection: MongoCollection<JsonObject>,
      type: String, id: String, field: String): T {
    val doc = collection.findOneAwait(json {
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

    return doc.getValue(field) as T
  }

  private suspend inline fun <reified T> getSubmissionField(id: String, field: String): T =
      getField(collSubmissions, "submission", id, field)

  private suspend inline fun <reified T> getProcessChainField(id: String, field: String): T =
      getField(collProcessChains, "process chain", id, field)

  override suspend fun fetchNextSubmission(currentStatus: Submission.Status,
      newStatus: Submission.Status): Submission? {
    val doc: JsonObject? = collSubmissions.findOneAndUpdateAwait(json {
      obj(
          STATUS to currentStatus.toString()
      )
    }, json {
      obj(
          "\$set" to obj(
              STATUS to newStatus.toString()
          )
      )
    }, FindOneAndUpdateOptions()
        .projection(wrap(SUBMISSION_EXCLUDES))
        .sort(wrap(json {
          obj(
              "$WORKFLOW.$PRIORITY" to -1,
              SEQUENCE to 1
          )
        })))
    return doc?.let { deserializeSubmission(it) }
  }

  override suspend fun setSubmissionStartTime(submissionId: String, startTime: Instant) {
    updateField(collSubmissions, submissionId, START_TIME, startTime)
  }

  override suspend fun setSubmissionEndTime(submissionId: String, endTime: Instant) {
    updateField(collSubmissions, submissionId, END_TIME, endTime)
  }

  override suspend fun setSubmissionStatus(submissionId: String,
      status: Submission.Status) {
    updateField(collSubmissions, submissionId, STATUS, status.toString())
  }

  override suspend fun getSubmissionStatus(submissionId: String) =
      getSubmissionField<String>(submissionId, STATUS).let {
        Submission.Status.valueOf(it)
      }

  override suspend fun setSubmissionPriority(submissionId: String,
      priority: Int): Boolean {
    val result = collSubmissions.updateOneAwait(jsonObjectOf(
        INTERNAL_ID to submissionId,
        "\$or" to jsonArrayOf(
            jsonObjectOf(
                STATUS to Submission.Status.ACCEPTED
            ),
            jsonObjectOf(
                STATUS to Submission.Status.RUNNING
            )
        )
    ), jsonObjectOf(
        "\$set" to jsonObjectOf(
            PRIORITY to priority
        )
    ))
    return result.modifiedCount > 0
  }

  private suspend fun writeGridFSDocument(bucket: GridFSBucket, id: String,
      obj: JsonObject?) {
    if (obj == null) {
      bucket.findAwait(json {
        obj(
            "filename" to id
        )
      })?.let {
        bucket.deleteAwait(it.id)
      }
    } else {
      val str = obj.encode()
      bucket.upload(id, ByteBuffer.wrap(str.toByteArray()))
    }
  }

  private suspend fun readGridFSDocument(bucket: GridFSBucket, id: String): Buffer? {
    try {
      return bucket.download(id)
    } catch (e: MongoGridFSException) {
      if (e.message == "File not found") {
        return null
      }
      throw e
    }
  }

  override suspend fun setSubmissionResults(submissionId: String, results: Map<String, List<Any>>?) {
    writeGridFSDocument(bucketSubmissionResults, submissionId,
        results?.let{ JsonObject(it) })
  }

  override suspend fun getSubmissionResults(submissionId: String): Map<String, List<Any>>? {
    val submissionCount = collSubmissions.countDocumentsAwait(json {
      obj(
          INTERNAL_ID to submissionId
      )
    })
    if (submissionCount == 0L) {
      throw NoSuchElementException("There is no submission with ID `$submissionId'")
    }

    val buf = readGridFSDocument(bucketSubmissionResults, submissionId)
    return buf?.let { JsonUtils.readValue(it.bytes) }
  }

  override suspend fun setSubmissionErrorMessage(submissionId: String,
      errorMessage: String?) {
    updateField(collSubmissions, submissionId, ERROR_MESSAGE, errorMessage)
  }

  override suspend fun getSubmissionErrorMessage(submissionId: String) =
      getSubmissionField<String?>(submissionId, ERROR_MESSAGE)

  override suspend fun setSubmissionExecutionState(submissionId: String, state: JsonObject?) {
    writeGridFSDocument(bucketExecutionStates, submissionId, state)
  }

  override suspend fun getSubmissionExecutionState(submissionId: String): JsonObject? {
    val buf = readGridFSDocument(bucketExecutionStates, submissionId)
    return buf?.let { JsonObject(it) }
  }

  override suspend fun deleteSubmissionsFinishedBefore(timestamp: Instant): Collection<String> {
    // find IDs of submissions whose end time is before the given timestamp
    val submissionIDs1 = collSubmissions.aggregateAwait(listOf(json {
      obj(
          "\$project" to obj(
              END_TIME to obj(
                  "\$toLong" to obj(
                      "\$toDate" to "\$$END_TIME"
                  )
              )
          )
      )
    }, json {
      obj(
          "\$match" to obj(
              END_TIME to obj(
                  "\$lt" to timestamp.toEpochMilli()
              )
          )
      )
    })).map { it.getString(INTERNAL_ID) }

    // find IDs of finished submissions that do not have an endTime but
    // whose ID was created before the given timestamp (this will also
    // include submissions without a startTime)
    val submissionIDs2 = collSubmissions.findAwait(json {
      obj(
        "\$and" to array(
          obj(
            STATUS to obj(
              "\$ne" to Submission.Status.ACCEPTED.toString()
            )
          ),
          obj(
            STATUS to obj(
              "\$ne" to Submission.Status.RUNNING.toString()
            )
          ),
          obj(
            END_TIME to null
          )
        )
      )
    }, projection = json {
      obj(
        INTERNAL_ID to 1
      )
    }).map { it.getString(INTERNAL_ID) }
      .filter { Instant.ofEpochMilli(UniqueID.toMillis(it)).isBefore(timestamp) }

    val submissionIDs = submissionIDs1 + submissionIDs2

    // delete 1000 submissions at once
    for (chunk in submissionIDs.chunked(1000)) {
      // delete process chains first
      collProcessChains.deleteManyAwait(json {
        obj(
            SUBMISSION_ID to obj(
                "\$in" to chunk
            )
        )
      })

      // then delete submissions
      collSubmissions.deleteManyAwait(json {
        obj(
            INTERNAL_ID to obj(
                "\$in" to chunk
            )
        )
      })
    }

    return submissionIDs
  }

  override suspend fun addProcessChains(processChains: Collection<ProcessChain>,
      submissionId: String, status: ProcessChainStatus) {
    val submissionCount = collSubmissions.countDocumentsAwait(json {
      obj(
          INTERNAL_ID to submissionId
      )
    })
    if (submissionCount == 0L) {
      throw NoSuchElementException("There is no submission with ID `$submissionId'")
    }

    val sequence = getNextSequence(COLL_PROCESS_CHAINS, processChains.size)
    val requests = processChains.mapIndexed { i, pc ->
      // write process chain without priority
      writeGridFSDocument(bucketProcessChains, pc.id, JsonUtils.toJson(pc.copy(priority = 0)))
      val doc = json {
        obj(
            INTERNAL_ID to pc.id,
            SEQUENCE to sequence + i,
            PRIORITY to -pc.priority, // negate priority so we can use compound index
            SUBMISSION_ID to submissionId,
            STATUS to status.toString(),
            REQUIRED_CAPABILITIES to JsonUtils.writeValueAsString(pc.requiredCapabilities)
        )
      }
      InsertOneModel(doc)
    }

    collProcessChains.bulkWriteAwait(requests)
  }

  /**
   * Handle process chain metadata [document] and read corresponding process
   * chain from GridFS. Return pair of process chain and submission ID
   */
  private suspend fun readProcessChain(document: JsonObject,
      excludeExecutables: Boolean = false): Pair<ProcessChain, String> {
    val submissionId = document.getString(SUBMISSION_ID, "")
    val id = document.getString(INTERNAL_ID)
    val priority = -document.getInteger(PRIORITY) // priorities are stored negated

    val buf = readGridFSDocument(bucketProcessChains, id)
        ?: throw IllegalStateException("Got process chain metadata with " +
            "ID `$id' but could not find corresponding object in GridFS bucket.")
    val mapper = if (excludeExecutables) ignoreExecutablesMapper else JsonUtils.mapper
    return Pair(mapper.readValue<ProcessChain>(buf.bytes).copy(priority = priority), submissionId)
  }

  override suspend fun findProcessChains(submissionId: String?,
      status: ProcessChainStatus?, size: Int, offset: Int, order: Int,
      excludeExecutables: Boolean) =
      collProcessChains.findAwait(JsonObject().also {
        if (submissionId != null) {
          it.put(SUBMISSION_ID, submissionId)
        }
        if (status != null) {
          it.put(STATUS, status.toString())
        }
      }, size, offset, json {
        obj(
            SEQUENCE to order
        )
      }, PROCESS_CHAIN_EXCLUDES_BUT_SUBMISSION_ID).map {
        readProcessChain(it, excludeExecutables) }

  override suspend fun findProcessChainIdsByStatus(status: ProcessChainStatus) =
      collProcessChains.findAwait(json {
        obj(
            STATUS to status.toString()
        )
      }, projection = json {
        obj(
            INTERNAL_ID to 1
        )
      }).map { it.getString(INTERNAL_ID) }

  override suspend fun findProcessChainIdsBySubmissionIdAndStatus(
      submissionId: String, status: ProcessChainStatus) =
      collProcessChains.findAwait(json {
        obj(
            SUBMISSION_ID to submissionId,
            STATUS to status.toString()
        )
      }, projection = json {
        obj(
            INTERNAL_ID to 1
        )
      }).map { it.getString(INTERNAL_ID) }

  override suspend fun findProcessChainStatusesBySubmissionId(submissionId: String) =
      collProcessChains.findAwait(json {
        obj(
            SUBMISSION_ID to submissionId
        )
      }, sort = json {
        obj(
            SEQUENCE to 1
        )
      }, projection = json {
        obj(
            INTERNAL_ID to 1,
            STATUS to 1
        )
      }).associateBy({ it.getString(INTERNAL_ID) }, {
        ProcessChainStatus.valueOf(it.getString(STATUS)) })

  override suspend fun findProcessChainRequiredCapabilities(
      status: ProcessChainStatus): List<Collection<String>> {
    val arrays = collProcessChains.distinctAwait(REQUIRED_CAPABILITIES, json {
      obj(
          STATUS to status.toString()
      )
    }, String::class.java)
    return arrays.map { a -> JsonArray(a).map { it.toString() }}
  }

  override suspend fun findProcessChainById(processChainId: String): ProcessChain? {
    val doc = collProcessChains.findOneAwait(json {
      obj(
          INTERNAL_ID to processChainId
      )
    }, PROCESS_CHAIN_EXCLUDES)
    return doc?.let { readProcessChain(it).first }
  }

  override suspend fun countProcessChains(submissionId: String?,
      status: ProcessChainStatus?, requiredCapabilities: Collection<String>?) =
      collProcessChains.countDocumentsAwait(JsonObject().also {
        if (submissionId != null) {
          it.put(SUBMISSION_ID, submissionId)
        }
        if (status != null) {
          it.put(STATUS, status.toString())
        }
        if (requiredCapabilities != null) {
          it.put(REQUIRED_CAPABILITIES, JsonUtils.writeValueAsString(requiredCapabilities))
        }
      })

  override suspend fun countProcessChainsPerStatus(submissionId: String?):
      Map<ProcessChainStatus, Long> {
    // db.processChains.aggregate([{$match:{submissionId:"aytd7wsvepytjxpdbisa"}},{$sortByCount:"$status"}])
    val pipeline = mutableListOf<JsonObject>()
    if (submissionId != null) {
      pipeline.add(json {
        obj(
            "\$match" to obj(
                SUBMISSION_ID to submissionId
            )
        )
      })
    }

    pipeline.add(json {
      obj(
          "\$sortByCount" to "\$$STATUS"
      )
    })

    return collProcessChains.aggregateAwait(pipeline).associateBy({
      ProcessChainStatus.valueOf(it.getString(INTERNAL_ID)) },  { it.getLong("count") })
  }

  override suspend fun fetchNextProcessChain(currentStatus: ProcessChainStatus,
      newStatus: ProcessChainStatus, requiredCapabilities: Collection<String>?): ProcessChain? {
    val doc = collProcessChains.findOneAndUpdateAwait(json {
      if (requiredCapabilities == null) {
        obj(
            STATUS to currentStatus.toString()
        )
      } else {
        obj(
            STATUS to currentStatus.toString(),
            REQUIRED_CAPABILITIES to JsonUtils.writeValueAsString(requiredCapabilities)
        )
      }
    }, json {
      obj(
          "\$set" to obj(
              STATUS to newStatus.toString()
          )
      )
    }, FindOneAndUpdateOptions()
        .projection(wrap(PROCESS_CHAIN_EXCLUDES))
        .sort(wrap(json {
          obj(
              PRIORITY to 1,
              SEQUENCE to 1
          )
        })))
    return doc?.let { readProcessChain(it).first }
  }

  override suspend fun existsProcessChain(currentStatus: ProcessChainStatus,
      requiredCapabilities: Collection<String>?): Boolean {
    return collProcessChains.countDocumentsAwait(json {
      if (requiredCapabilities == null) {
        obj(
            STATUS to currentStatus.toString()
        )
      } else {
        obj(
            STATUS to currentStatus.toString(),
            REQUIRED_CAPABILITIES to JsonUtils.writeValueAsString(requiredCapabilities)
        )
      }
    }, 1) == 1L
  }

  override suspend fun setProcessChainStartTime(processChainId: String, startTime: Instant?) {
    updateField(collProcessChains, processChainId, START_TIME, startTime)
  }

  override suspend fun getProcessChainStartTime(processChainId: String): Instant? =
      getProcessChainField<String?>(processChainId, START_TIME)?.let {
        Instant.from(ISO_INSTANT.parse(it))
      }

  override suspend fun setProcessChainEndTime(processChainId: String, endTime: Instant?) {
    updateField(collProcessChains, processChainId, END_TIME, endTime)
  }

  override suspend fun getProcessChainEndTime(processChainId: String): Instant? =
      getProcessChainField<String?>(processChainId, END_TIME)?.let {
        Instant.from(ISO_INSTANT.parse(it))
      }

  override suspend fun getProcessChainSubmissionId(processChainId: String): String =
      getProcessChainField(processChainId, SUBMISSION_ID)

  override suspend fun setProcessChainStatus(processChainId: String,
      status: ProcessChainStatus) {
    updateField(collProcessChains, processChainId, STATUS, status.toString())
  }

  override suspend fun setProcessChainStatus(processChainId: String,
      currentStatus: ProcessChainStatus, newStatus: ProcessChainStatus) {
    updateField(collProcessChains, processChainId, STATUS,
        currentStatus.toString(), newStatus.toString())
  }

  override suspend fun setAllProcessChainsStatus(submissionId: String,
      currentStatus: ProcessChainStatus, newStatus: ProcessChainStatus) {
    collProcessChains.updateManyAwait(json {
      obj(
          SUBMISSION_ID to submissionId,
          STATUS to currentStatus.toString()
      )
    }, json {
      obj(
          "\$set" to obj(
              STATUS to newStatus.toString()
          )
      )
    })
  }

  override suspend fun getProcessChainStatus(processChainId: String) =
      getProcessChainField<String>(processChainId, STATUS).let {
        ProcessChainStatus.valueOf(it)
      }

  override suspend fun setProcessChainPriority(processChainId: String, priority: Int): Boolean {
    val result = collProcessChains.updateOneAwait(jsonObjectOf(
        INTERNAL_ID to processChainId,
        "\$or" to jsonArrayOf(
            jsonObjectOf(
                STATUS to ProcessChainStatus.REGISTERED
            ),
            jsonObjectOf(
                STATUS to ProcessChainStatus.RUNNING
            )
        )
    ), jsonObjectOf(
        "\$set" to jsonObjectOf(
            // priorities are stored negated
            PRIORITY to -priority
        )
    ))
    return result.modifiedCount > 0
  }

  override suspend fun setAllProcessChainsPriority(submissionId: String, priority: Int) {
    collProcessChains.updateManyAwait(jsonObjectOf(
        SUBMISSION_ID to submissionId,
        "\$or" to jsonArrayOf(
            jsonObjectOf(
                STATUS to ProcessChainStatus.REGISTERED
            ),
            jsonObjectOf(
                STATUS to ProcessChainStatus.RUNNING
            )
        )
    ), jsonObjectOf(
        "\$set" to jsonObjectOf(
            // priorities are stored negated
            PRIORITY to -priority
        )
    ))
  }

  override suspend fun setProcessChainResults(processChainId: String,
      results: Map<String, List<Any>>?) {
    writeGridFSDocument(bucketProcessChainResults, processChainId,
        results?.let{ JsonObject(it) })
  }

  private suspend fun getProcessChainResultsInternal(processChainId: String): Map<String, List<Any>>? {
    val buf = readGridFSDocument(bucketProcessChainResults, processChainId)
    return buf?.let { JsonUtils.readValue(it.bytes) }
  }

  override suspend fun getProcessChainResults(processChainId: String): Map<String, List<Any>>? {
    val processChainCount = collProcessChains.countDocumentsAwait(json {
      obj(
          INTERNAL_ID to processChainId
      )
    })
    if (processChainCount == 0L) {
      throw NoSuchElementException("There is no process chain with ID `$processChainId'")
    }
    return getProcessChainResultsInternal(processChainId)
  }

  override suspend fun getProcessChainStatusAndResultsIfFinished(processChainIds: Collection<String>):
      Map<String, Pair<ProcessChainStatus, Map<String, List<Any>>?>> {
    val finished = collProcessChains.findAwait(json {
      obj(
          "\$and" to array(
              obj(
                  STATUS to obj(
                      "\$ne" to ProcessChainStatus.REGISTERED.toString()
                  ),
              ),
              obj(
                  STATUS to obj(
                      "\$ne" to ProcessChainStatus.RUNNING.toString()
                  )
              )
          ),
          INTERNAL_ID to obj(
              "\$in" to processChainIds.toList()
          )
      )
    }, projection = json {
      obj(
          INTERNAL_ID to 1,
          STATUS to 1
      )
    }).associateBy({ it.getString(INTERNAL_ID) }, {
      ProcessChainStatus.valueOf(it.getString(STATUS)) })

    // shortcut: only successful process chains can have a result
    return finished.mapValues { f ->
      val results = if (f.value == ProcessChainStatus.SUCCESS) {
        getProcessChainResultsInternal(f.key)
      } else {
        null
      }
      f.value to results
    }
  }

  override suspend fun setProcessChainErrorMessage(processChainId: String, errorMessage: String?) {
    updateField(collProcessChains, processChainId, ERROR_MESSAGE, errorMessage)
  }

  override suspend fun getProcessChainErrorMessage(processChainId: String): String? =
      getProcessChainField(processChainId, ERROR_MESSAGE)

  override suspend fun search(query: Query, size: Int, offset: Int,
      order: Int): Collection<SearchResult> {
    TODO("Not yet implemented")
  }

  override suspend fun searchCount(query: Query, type: Type, estimate: Boolean): Long {
    TODO("Not yet implemented")
  }
}
