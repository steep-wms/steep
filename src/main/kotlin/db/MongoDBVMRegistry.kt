package db

import com.mongodb.client.model.IndexModel
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.reactivestreams.client.MongoCollection
import helper.DefaultSubscriber
import helper.JsonUtils
import helper.UniqueID
import helper.aggregateAwait
import helper.countDocumentsAwait
import helper.deleteManyAwait
import helper.findAwait
import helper.findOneAwait
import helper.insertOneAwait
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import model.cloud.VM
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * A VM registry that keeps objects in a MongoDB database
 * @param vertx the current Vert.x instance
 * @param connectionString the MongoDB connection string (e.g.
 * `mongodb://localhost:27017/database`)
 * @param createIndexes `true` if indexes should be created
 * @author Michel Kraemer
 */
class MongoDBVMRegistry(private val vertx: Vertx,
    connectionString: String, createIndexes: Boolean = true) :
    MongoDBRegistry(vertx, connectionString), VMRegistry {
  companion object {
    private val log = LoggerFactory.getLogger(MongoDBVMRegistry::class.java)

    /**
     * Collection and property names
     */
    private const val COLL_VMS = "vms"
    private const val EXTERNAL_ID = "externalId"
    private const val IP_ADDRESS = "ipAddress"
    private const val SETUP = "setup"
    private const val CREATION_TIME = "creationTime"
    private const val AGENT_JOIN_TIME = "agentJoinTime"
    private const val DESTRUCTION_TIME = "destructionTime"
    private const val STATUS = "status"
    private const val REASON = "reason"
    private const val SEQUENCE = "sequence"

    private val NON_TERMINATED_QUERY = json {
      "\$and" to array(
          obj(
              STATUS to obj(
                  "\$ne" to VM.Status.DESTROYED.toString()
              )
          ),
          obj(
              STATUS to obj(
                  "\$ne" to VM.Status.ERROR.toString()
              )
          )
      )
    }

    private val STARTING_QUERY = json {
      "\$or" to array(
          obj(
              STATUS to VM.Status.CREATING.toString()
          ),
          obj(
              STATUS to VM.Status.PROVISIONING.toString()
          )
      )
    }
  }

  private val collVMs: MongoCollection<JsonObject> =
      db.getCollection(COLL_VMS, JsonObject::class.java)

  init {
    if (createIndexes) {
      collVMs.createIndexes(listOf(
          IndexModel(Indexes.ascending(EXTERNAL_ID), IndexOptions().background(true)),
          IndexModel(Indexes.ascending(STATUS), IndexOptions().background(true)),
          IndexModel(Indexes.ascending("$SETUP.$ID"), IndexOptions().background(true)),
          IndexModel(Indexes.ascending(SEQUENCE), IndexOptions().background(true))
      )).subscribe(object : DefaultSubscriber<String>() {
        override fun onError(t: Throwable) {
          log.error("Could not create index on collection `${COLL_VMS}'", t)
        }
      })
    }
  }

  override suspend fun addVM(vm: VM) {
    val sequence = getNextSequence(COLL_VMS)
    val doc = JsonUtils.toJson(vm)
    doc.put(INTERNAL_ID, vm.id)
    doc.remove(ID)
    doc.put(SEQUENCE, sequence)
    collVMs.insertOneAwait(doc)
  }

  /**
   * Deserialize a VM from a database [document]
   */
  private fun deserializeVM(document: JsonObject): VM {
    document.remove(SEQUENCE)
    document.put(ID, document.getString(INTERNAL_ID))
    document.remove(INTERNAL_ID)
    return JsonUtils.fromJson(document)
  }

  override suspend fun findVMs(status: VM.Status?, size: Int, offset: Int,
      order: Int): Collection<VM> {
    val docs = collVMs.findAwait(JsonObject().also {
      if (status != null) {
        it.put(STATUS, status.toString())
      }
    }, size, offset, json {
      obj(
          SEQUENCE to order
      )
    })
    return docs.map { deserializeVM(it) }
  }

  override suspend fun findVMById(id: String): VM? {
    val doc = collVMs.findOneAwait(json {
      obj(
          INTERNAL_ID to id
      )
    })
    return doc?.let { deserializeVM(it) }
  }

  override suspend fun findVMByExternalId(externalId: String): VM? {
    val doc = collVMs.findOneAwait(json {
      obj(
          EXTERNAL_ID to externalId
      )
    })
    return doc?.let { deserializeVM(it) }
  }

  override suspend fun findNonTerminatedVMs(): Collection<VM> {
    val docs = collVMs.findAwait(json {
      obj(
          NON_TERMINATED_QUERY
      )
    })
    return docs.map { deserializeVM(it) }
  }

  override suspend fun countVMs(status: VM.Status?) =
      collVMs.countDocumentsAwait(JsonObject().also {
        if (status != null) {
          it.put(STATUS, status.toString())
        }
      })

  override suspend fun countNonTerminatedVMsBySetup(setupId: String): Long {
    return collVMs.countDocumentsAwait(json {
      obj(
          NON_TERMINATED_QUERY,
          "$SETUP.$ID" to setupId
      )
    })
  }

  override suspend fun countStartingVMsBySetup(setupId: String): Long {
    return collVMs.countDocumentsAwait(json {
      obj(
          STARTING_QUERY,
          "$SETUP.$ID" to setupId
      )
    })
  }

  private suspend inline fun <reified T> getField(id: String, field: String): T {
    val doc = collVMs.findOneAwait(json {
      obj(
          INTERNAL_ID to id
      )
    }, json {
      obj(
          field to 1
      )
    }) ?: throw NoSuchElementException("There is no VM with ID `$id'")
    return doc.getValue(field) as T
  }

  override suspend fun setVMCreationTime(id: String, creationTime: Instant) {
    updateField(collVMs, id, CREATION_TIME, creationTime)
  }

  override suspend fun setVMAgentJoinTime(id: String, agentJoinTime: Instant) {
    updateField(collVMs, id, AGENT_JOIN_TIME, agentJoinTime)
  }

  override suspend fun setVMDestructionTime(id: String, destructionTime: Instant) {
    updateField(collVMs, id, DESTRUCTION_TIME, destructionTime)
  }

  override suspend fun setVMStatus(id: String, currentStatus: VM.Status,
      newStatus: VM.Status) {
    updateField(collVMs, id, STATUS, currentStatus.toString(), newStatus.toString())
  }

  override suspend fun forceSetVMStatus(id: String, newStatus: VM.Status) {
    updateField(collVMs, id, STATUS, newStatus.toString())
  }

  override suspend fun getVMStatus(id: String) = getField<String>(id, STATUS).let {
    VM.Status.valueOf(it)
  }

  override suspend fun setVMExternalID(id: String, externalId: String) {
    updateField(collVMs, id, EXTERNAL_ID, externalId)
  }

  override suspend fun setVMIPAddress(id: String, ipAddress: String) {
    updateField(collVMs, id, IP_ADDRESS, ipAddress)
  }

  override suspend fun setVMReason(id: String, reason: String?) {
    updateField(collVMs, id, REASON, reason)
  }

  override suspend fun deleteVMsDestroyedBefore(timestamp: Instant): Collection<String> {
    // find IDs of VMs whose destruction time is before the given timestamp
    val ids1 = collVMs.aggregateAwait(listOf(json {
      obj(
          "\$project" to obj(
              DESTRUCTION_TIME to obj(
                  "\$toLong" to obj(
                      "\$toDate" to "\$$DESTRUCTION_TIME"
                  )
              )
          )
      )
    }, json {
      obj(
          "\$match" to obj(
              DESTRUCTION_TIME to obj(
                  "\$lt" to timestamp.toEpochMilli()
              )
          )
      )
    })).map { it.getString(INTERNAL_ID) }

    // find IDs of terminated VMs that do not have a destructionTime but
    // whose ID was created before the given timestamp (this will also
    // include VMs without a creationTime)
    val ids2 = collVMs.findAwait(json {
      obj(
        "\$or" to array(
          obj(
            STATUS to VM.Status.DESTROYED.toString()
          ),
          obj(
            STATUS to VM.Status.ERROR.toString()
          )
        ),
        DESTRUCTION_TIME to null
      )
    }, projection = json {
      obj(
        INTERNAL_ID to 1
      )
    }).map { it.getString(INTERNAL_ID) }
      .filter { Instant.ofEpochMilli(UniqueID.toMillis(it)).isBefore(timestamp) }

    val ids = ids1 + ids2

    // delete 1000 VMs at once
    for (chunk in ids.chunked(1000)) {
      collVMs.deleteManyAwait(json {
        obj(
            INTERNAL_ID to obj(
                "\$in" to chunk
            )
        )
      })
    }

    return ids
  }
}
