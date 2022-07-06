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
import search.DateTerm
import search.DateTimeRangeTerm
import search.DateTimeTerm
import search.Locator
import search.Operator
import search.Query
import search.SearchResult
import search.StringTerm
import search.Term
import search.Type
import java.nio.ByteBuffer
import java.time.Instant
import java.time.format.DateTimeFormatter.ISO_INSTANT
import java.time.temporal.ChronoUnit
import java.util.regex.Pattern
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
    MongoDBRegistry(vertx, connectionString), SubmissionRegistry {
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
    private const val NAME = "name"
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

    // store startTime and endTime as BSON timestamps
    submission.startTime?.let { st ->
      doc.put(START_TIME, instantToTimestamp(st))
    }
    submission.endTime?.let { et ->
      doc.put(END_TIME, instantToTimestamp(et))
    }

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

    // convert BSON timestamps to ISO strings
    document.getJsonObject(START_TIME)?.let { st ->
      document.put(START_TIME, ISO_INSTANT.format(timestampToInstant(st)))
    }
    document.getJsonObject(END_TIME)?.let { et ->
      document.put(END_TIME, ISO_INSTANT.format(timestampToInstant(et)))
    }

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
    updateField(collSubmissions, submissionId, START_TIME, instantToTimestamp(startTime))
  }

  override suspend fun setSubmissionEndTime(submissionId: String, endTime: Instant) {
    updateField(collSubmissions, submissionId, END_TIME, instantToTimestamp(endTime))
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
    val submissionIDs1 = collSubmissions.findAwait(jsonObjectOf(
        END_TIME to jsonObjectOf(
            "\$lt" to instantToTimestamp(timestamp)
        )
    ), projection = jsonObjectOf(
        INTERNAL_ID to 1
    )).map { it.getString(INTERNAL_ID) }

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
      status: ProcessChainStatus): List<Pair<Collection<String>, IntRange>> {
    val result = collProcessChains.aggregateAwait(listOf(
        jsonObjectOf(
            "\$match" to jsonObjectOf(
                STATUS to status.toString()
            )
        ),
        jsonObjectOf(
            "\$group" to jsonObjectOf(
                "_id" to "\$$REQUIRED_CAPABILITIES",
                // priorities are stored negated
                "minPriority" to jsonObjectOf("\$max" to "\$$PRIORITY"),
                "maxPriority" to jsonObjectOf("\$min" to "\$$PRIORITY"),
            )
        )
    ))
    return result.map { r ->
      val rcs = JsonArray(r.getString("_id")).map { it.toString() }
      // priorities are stored negated
      val minPriority = -r.getInteger("minPriority")
      val maxPriority = -r.getInteger("maxPriority")
      rcs to minPriority..maxPriority
    }
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
      status: ProcessChainStatus?, requiredCapabilities: Collection<String>?,
      minPriority: Int?) =
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
        if (minPriority != null) {
          // priorities are stored negated
          it.put(PRIORITY, jsonObjectOf("\$lte" to -minPriority))
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
      newStatus: ProcessChainStatus, requiredCapabilities: Collection<String>?,
      minPriority: Int?): ProcessChain? {
    val doc = collProcessChains.findOneAndUpdateAwait(JsonObject().also {
      it.put(STATUS, currentStatus.toString())
      if (requiredCapabilities != null) {
        it.put(REQUIRED_CAPABILITIES, JsonUtils.writeValueAsString(requiredCapabilities))
      }
      if (minPriority != null) {
        // priorities are stored negated
        it.put(PRIORITY, jsonObjectOf("\$lte" to -minPriority))
      }
    }, jsonObjectOf(
      "\$set" to jsonObjectOf(
          STATUS to newStatus.toString()
      )
    ), FindOneAndUpdateOptions()
        .projection(wrap(PROCESS_CHAIN_EXCLUDES))
        .sort(wrap(jsonObjectOf(
            PRIORITY to 1,
            SEQUENCE to 1
        ))))
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
    updateField(collProcessChains, processChainId, START_TIME, instantToTimestamp(startTime))
  }

  override suspend fun getProcessChainStartTime(processChainId: String): Instant? =
      getProcessChainField<Any?>(processChainId, START_TIME)?.let {
        timestampToInstant(it)
      }

  override suspend fun setProcessChainEndTime(processChainId: String, endTime: Instant?) {
    updateField(collProcessChains, processChainId, END_TIME, instantToTimestamp(endTime))
  }

  override suspend fun getProcessChainEndTime(processChainId: String): Instant? =
      getProcessChainField<Any?>(processChainId, END_TIME)?.let {
        timestampToInstant(it)
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

  /**
   * Create an operator that looks for a [value] in a given [field]. Either
   * create a $regex operator if [aggregation] is `false` or create an
   * aggregation operator $regexMatch
   */
  private fun makeRegex(field: String, value: String, aggregation: Boolean): JsonObject {
    return if (aggregation) {
      jsonObjectOf(
          "\$regexMatch" to jsonObjectOf(
              "input" to "\$$field",
              "regex" to Pattern.quote(value),
              "options" to "i" // ignore case
          )
      )
    } else {
      jsonObjectOf(
          field to jsonObjectOf(
              "\$regex" to Pattern.quote(value),
              "\$options" to "i" // ignore case
          )
      )
    }
  }

  /**
   * Create an expression that compares a timestamp stored in a [field] with
   * the given [start] time and [endExclusive] time. Either create an [aggregation]
   * or a normal comparison expression.
   */
  private fun makeTimestampComparison(field: String, start: Instant,
      endExclusive: Instant, operator: Operator, aggregation: Boolean): JsonObject {
    return if (aggregation) {
      val f = "\$$field"
      when (operator) {
        Operator.LT -> jsonObjectOf("\$lt" to jsonArrayOf(f, instantToTimestamp(start)))
        Operator.LTE -> jsonObjectOf("\$lt" to jsonArrayOf(f, instantToTimestamp(endExclusive)))
        Operator.EQ -> jsonObjectOf("\$and" to jsonArrayOf(
            jsonObjectOf("\$gte" to jsonArrayOf(f, instantToTimestamp(start))),
            jsonObjectOf("\$lt" to jsonArrayOf(f, instantToTimestamp(endExclusive)))
        ))
        Operator.GTE -> jsonObjectOf("\$gte" to jsonArrayOf(f, instantToTimestamp(start)))
        Operator.GT -> jsonObjectOf("\$gte" to jsonArrayOf(f, instantToTimestamp(endExclusive)))
      }
    } else {
      jsonObjectOf(field to when (operator) {
        Operator.LT -> jsonObjectOf("\$lt" to instantToTimestamp(start))
        Operator.LTE -> jsonObjectOf("\$lt" to instantToTimestamp(endExclusive))
        Operator.EQ -> jsonObjectOf("\$gte" to instantToTimestamp(start),
            "\$lt" to instantToTimestamp(endExclusive))
        Operator.GTE -> jsonObjectOf("\$gte" to instantToTimestamp(start))
        Operator.GT -> jsonObjectOf("\$gte" to instantToTimestamp(endExclusive))
      })
    }
  }

  /**
   * Converts a [locator] to a field name
   */
  private fun locatorToField(locator: Locator, type: Type,
      aggregation: Boolean = false): String? = when (locator) {
    Locator.ERROR_MESSAGE -> ERROR_MESSAGE
    Locator.ID -> INTERNAL_ID
    Locator.NAME -> when (type) {
      Type.WORKFLOW -> NAME
      Type.PROCESS_CHAIN -> null
    }
    Locator.REQUIRED_CAPABILITIES -> if (type == Type.WORKFLOW && aggregation) {
      // When we determine the document's rank, we need to use aggregation
      // operators, which cannot handle arrays. So, we use the `joinedRequiredCapabilities`
      // field here instead, which is a string representation of the array.
      "joinedRequiredCapabilities"
    } else {
      REQUIRED_CAPABILITIES
    }
    Locator.SOURCE -> when (type) {
      Type.WORKFLOW -> SOURCE
      Type.PROCESS_CHAIN -> null
    }
    Locator.STATUS -> STATUS
    Locator.START_TIME -> START_TIME
    Locator.END_TIME -> END_TIME
  }

  /**
   * Create condition from a [locator] and a [term]. Create an [aggregation]
   * operator if necessary. Otherwise, create a normal $match condition.
   */
  private fun makeCondition(locator: Locator, term: Term, type: Type,
      aggregation: Boolean): JsonObject? {
    return when (locator) {
      Locator.ERROR_MESSAGE, Locator.ID -> {
        when (term) {
          is StringTerm -> locatorToField(locator, type)?.let { f ->
            makeRegex(f, term.value, aggregation) }
          else -> null
        }
      }

      Locator.STATUS -> {
        when (term) {
          is StringTerm -> (when (type) {
            Type.WORKFLOW -> Submission.Status.values().find {
              it.name.contains(term.value, true) }?.name
            Type.PROCESS_CHAIN -> ProcessChainStatus.values().find {
              it.name.contains(term.value, true) }?.name
          })?.let { status -> locatorToField(locator, type)?.let { f -> jsonObjectOf(f to status) } }
          else -> null
        }
      }

      // submission only!
      Locator.NAME, Locator.SOURCE -> {
        if (type == Type.WORKFLOW) {
          when (term) {
            is StringTerm -> locatorToField(locator, type)?.let { f ->
              makeRegex(f, term.value, aggregation) }
            else -> null
          }
        } else {
          null
        }
      }

      Locator.REQUIRED_CAPABILITIES -> {
        when (term) {
          is StringTerm -> locatorToField(locator, type, aggregation)?.let { f ->
            makeRegex(f, term.value, aggregation) }
          else -> null
        }
      }

      Locator.START_TIME, Locator.END_TIME -> {
        locatorToField(locator, type)?.let { f ->
          when (term) {
            is DateTerm -> makeTimestampComparison(f,
                term.value.atStartOfDay(term.timeZone).toInstant(),
                term.value.plusDays(1).atStartOfDay(term.timeZone).toInstant(),
                term.operator, aggregation)

            is DateTimeTerm -> {
              if (term.withSecondPrecision) {
                makeTimestampComparison(f,
                    term.value.truncatedTo(ChronoUnit.SECONDS)
                        .atZone(term.timeZone).toInstant(),
                    term.value.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1)
                        .atZone(term.timeZone).toInstant(),
                    term.operator, aggregation)
              } else {
                makeTimestampComparison(f,
                    term.value.truncatedTo(ChronoUnit.MINUTES)
                        .atZone(term.timeZone).toInstant(),
                    term.value.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
                        .atZone(term.timeZone).toInstant(),
                    term.operator, aggregation)
              }
            }

            is DateTimeRangeTerm -> {
              val start = (if (term.fromInclusiveTime != null) {
                (if (term.fromWithSecondPrecision) {
                  term.fromInclusiveDate.atTime(term.fromInclusiveTime)
                      .truncatedTo(ChronoUnit.SECONDS)
                } else {
                  term.fromInclusiveDate.atTime(term.fromInclusiveTime)
                      .truncatedTo(ChronoUnit.MINUTES)
                }).atZone(term.timeZone)
              } else {
                term.fromInclusiveDate.atStartOfDay(term.timeZone)
              }).toInstant()

              val endExclusive = (if (term.toInclusiveTime != null) {
                (if (term.toWithSecondPrecision) {
                  term.toInclusiveDate.atTime(term.toInclusiveTime)
                      .truncatedTo(ChronoUnit.SECONDS).plusSeconds(1)
                } else {
                  term.toInclusiveDate.atTime(term.toInclusiveTime)
                      .truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
                }).atZone(term.timeZone)
              } else {
                term.toInclusiveDate.plusDays(1).atStartOfDay(term.timeZone)
              }).toInstant()

              makeTimestampComparison(f, start, endExclusive, Operator.EQ,
                  aggregation)
            }

            else -> null
          }
        }
      }
    }
  }

  override suspend fun search(query: Query, size: Int, offset: Int,
      order: Int): Collection<SearchResult> {
    // TODO if the search becomes to slow, add indexes for all attributes we search
    // TODO at the moment, performance is quite OK

    if (query == Query() || size == 0) {
      return emptyList()
    }

    // search in all places by default
    val types = query.types.ifEmpty { Type.values().toSet() }
    val locators = if (query.terms.isNotEmpty()) {
      query.locators.ifEmpty { Locator.values().toSet() }
    } else {
      emptyList()
    }

    val pipelines = mutableMapOf<Type, MutableList<JsonObject>>()
    val rankConditions = mutableMapOf<Type, JsonArray>()
    for (type in types) {
      // make a condition for each filter
      val filters = JsonArray()
      for (f in query.filters) {
        makeCondition(f.first, f.second, type, false)?.let { filters.add(it) }
      }

      // make a condition for each term
      val terms = JsonArray()
      for (term in query.terms) {
        val aggregateConditions = JsonArray()
        for (locator in locators) {
          makeCondition(locator, term, type, false)?.let { terms.add(it) }
          makeCondition(locator, term, type, true)?.let { aggregateConditions.add(it) }
        }
        if (!aggregateConditions.isEmpty) {
          rankConditions.computeIfAbsent(type) { JsonArray() }.add(jsonObjectOf(
              "\$cond" to jsonArrayOf(
                  jsonObjectOf(
                      "\$or" to aggregateConditions
                  ),
                  1, 0
              )
          ))
        }
      }

      val match = jsonObjectOf()
      if (!terms.isEmpty) {
        match.put("\$or", terms)
      }
      if (!filters.isEmpty) {
        match.put("\$and", filters)
      }

      if (!match.isEmpty) {
        val matchStage = jsonObjectOf(
            "\$match" to match
        )
        pipelines.computeIfAbsent(type) { mutableListOf() }.add(matchStage)
      }
    }

    if (pipelines.isEmpty() || pipelines.all { it.value.isEmpty() }) {
      // nothing to do
      return emptyList()
    }

    // TODO If the search is too slow for very large databases, we could do
    // TODO something like the PostgreSQLSubmissionRegistry does and limit
    // TODO the results to the first 1000 newest documents. For the time begin
    // TODO search speed is actually quite OK
    // for (type in types) {
    //   val pl = pipelines[type] ?: continue
    //   pl.add(jsonObjectOf(
    //       "\$sort" to jsonObjectOf(
    //           SEQUENCE to -order
    //       )
    //   ))
    //   pl.add(jsonObjectOf(
    //       "\$limit" to 1000
    //   ))
    // }

    if (locators.contains(Locator.REQUIRED_CAPABILITIES)) {
      // add 'joinedRequiredCapabilities' field that we can use to calculate 'rank'
      // this field contains a string representation of the `requiredCapabilities`
      // array with values separated by a non-breaking space
      pipelines[Type.WORKFLOW]?.add(jsonObjectOf(
          "\$addFields" to jsonObjectOf(
              "joinedRequiredCapabilities" to jsonObjectOf(
                  "\$reduce" to jsonObjectOf(
                      "input" to "\$$REQUIRED_CAPABILITIES",
                      "initialValue" to "",
                      "in" to jsonObjectOf(
                          "\$concat" to jsonArrayOf("\$\$value", "\$\$this", "\u00a0")
                      )
                  )
              )
          )
      ))
    }

    // determine rank for each match
    val rankConditionCounts = mutableMapOf<Type, Int>()
    for ((type, conds) in rankConditions) {
      val pl = pipelines[type] ?: continue
      var count = 0
      val fieldsToAdd = JsonObject()
      for (cond in conds) {
        fieldsToAdd.put("rank_$count", cond)
        count++
      }
      if (!fieldsToAdd.isEmpty) {
        pl.add(jsonObjectOf(
            "\$addFields" to fieldsToAdd
        ))
      }
      rankConditionCounts[type] = count
    }

    // determine which columns we need to return (some fields are mandatory
    // in SearchResults)
    val columns = mutableSetOf(Locator.ID, Locator.NAME, Locator.STATUS,
        Locator.REQUIRED_CAPABILITIES, Locator.START_TIME, Locator.END_TIME)
    for (l in locators) {
      columns.add(l)
    }
    for ((l, _) in query.filters) {
      columns.add(l)
    }

    for (type in types) {
      val pl = pipelines[type] ?: continue

      val projectedFields = JsonObject()
      for (c in columns) {
        val f = locatorToField(c, type)
        if (f != null) {
          projectedFields.put(f, 1)
        }
      }
      projectedFields.put(SEQUENCE, 1)
      val rankConditionCount = rankConditionCounts[type] ?: 0
      projectedFields.put("rank", jsonObjectOf(
          "\$add" to JsonArray((0 until rankConditionCount).map { "\$rank_$it"})
      ))
      val project = jsonObjectOf(
          "\$project" to projectedFields
      )

      val addTypeSubmissions = jsonObjectOf(
          "\$addFields" to jsonObjectOf(
              "type" to type.priority
          )
      )

      pl.add(project)
      pl.add(addTypeSubmissions)
    }

    val pipeline = mutableListOf<JsonObject>()
    val collection = if (pipelines[Type.WORKFLOW]?.isNotEmpty() == true &&
        pipelines[Type.PROCESS_CHAIN]?.isNotEmpty() == true) {
      pipeline.addAll(pipelines[Type.WORKFLOW]!!)
      pipeline.add(jsonObjectOf(
          "\$unionWith" to jsonObjectOf(
              "coll" to COLL_PROCESS_CHAINS,
              "pipeline" to pipelines[Type.PROCESS_CHAIN]!!
          )
      ))
      collSubmissions
    } else if (pipelines[Type.WORKFLOW]?.isNotEmpty() == true) {
      pipeline.addAll(pipelines[Type.WORKFLOW]!!)
      collSubmissions
    } else {
      pipeline.addAll(pipelines[Type.PROCESS_CHAIN]!!)
      collProcessChains
    }

    // sort results
    pipeline.add(jsonObjectOf(
        "\$sort" to jsonObjectOf(
            "rank" to -order,
            "type" to order,
            SEQUENCE to -order
        )
    ))

    if (offset > 0) {
      pipeline.add(jsonObjectOf(
          "\$skip" to offset
      ))
    }

    if (size > 0) {
      pipeline.add(jsonObjectOf(
          "\$limit" to size
      ))
    }

    return collection.aggregateAwait(pipeline).map { r ->
      if (r.getValue(REQUIRED_CAPABILITIES) is String) {
        // required capabilities are stored as strings in process chain documents
        r.put(REQUIRED_CAPABILITIES, JsonArray(r.getString(REQUIRED_CAPABILITIES)))
      }

      // rename internal ID
      r.put(ID, r.getString(INTERNAL_ID))
      r.remove(INTERNAL_ID)

      // remove auxiliary fields
      r.remove("rank")
      r.remove(SEQUENCE)

      // convert BSON timestamps to ISO strings
      r.getJsonObject(START_TIME)?.let { st ->
        r.put(START_TIME, ISO_INSTANT.format(timestampToInstant(st)))
      }
      r.getJsonObject(END_TIME)?.let { et ->
        r.put(END_TIME, ISO_INSTANT.format(timestampToInstant(et)))
      }

      JsonUtils.fromJson(r)
    }
  }

  override suspend fun searchCount(query: Query, type: Type, estimate: Boolean): Long {
    if (query == Query()) {
      return 0L
    }

    // search in all places by default
    val locators = if (query.terms.isNotEmpty()) {
      query.locators.ifEmpty { Locator.values().toSet() }
    } else {
      emptyList()
    }

    // make a condition for each filter
    val filters = JsonArray()
    for (f in query.filters) {
      makeCondition(f.first, f.second, type, false)?.let { filters.add(it) }
    }

    // make a condition for each term
    val terms = JsonArray()
    for (term in query.terms) {
      for (locator in locators) {
        makeCondition(locator, term, type, false)?.let { terms.add(it) }
      }
    }

    val match = jsonObjectOf()
    if (!terms.isEmpty) {
      match.put("\$or", terms)
    }
    if (!filters.isEmpty) {
      match.put("\$and", filters)
    }

    if (match.isEmpty) {
      // nothing to do
      return 0L
    }

    val coll = when (type) {
      Type.WORKFLOW -> collSubmissions
      Type.PROCESS_CHAIN -> collProcessChains
    }

    return coll.countDocumentsAwait(match)
  }
}
