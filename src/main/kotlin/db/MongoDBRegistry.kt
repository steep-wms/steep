package db

import com.mongodb.ConnectionString
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.reactivestreams.client.MongoClient
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import helper.findOneAndUpdateAwait
import helper.updateOneAwait
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.mongo.impl.codec.json.JsonObjectCodec
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.core.json.obj
import java.time.Instant
import java.time.format.DateTimeFormatter.ISO_INSTANT

/**
 * Base class for registries that access a MongoDB database
 * @param connectionString the MongoDB connection string (e.g.
 * `mongodb://localhost:27017/database`)
 * @author Michel Kraemer
 */
open class MongoDBRegistry(vertx: Vertx, connectionString: String) : Registry {
  companion object {
    /**
     * Collection and property names
     */
    private const val COLL_SEQUENCE = "sequence"
    @JvmStatic protected val INTERNAL_ID = "_id"
    @JvmStatic protected val ID = "id"
    private const val VALUE = "value"
  }

  protected val client: MongoClient
  protected val db: MongoDatabase

  private val collSequence: MongoCollection<JsonObject>

  init {
    val cs = ConnectionString(connectionString)
    client = SharedMongoClient.create(vertx, cs)
    db = client.getDatabase(cs.database)

    collSequence = db.getCollection(COLL_SEQUENCE, JsonObject::class.java)
  }

  override suspend fun close() {
    client.close()
  }

  /**
   * Get a next [n] sequential numbers for a given [collection]
   */
  protected suspend fun getNextSequence(collection: String, n: Int = 1): Long {
    val doc = collSequence.findOneAndUpdateAwait(json {
      obj(
          INTERNAL_ID to collection
      )
    }, json {
      obj(
          "\$inc" to obj(
              VALUE to n.toLong()
          )
      )
    }, FindOneAndUpdateOptions().upsert(true))
    return doc?.getLong(VALUE, 0L) ?: 0L
  }

  /**
   * Set a [field] of a document with a given [id] in the given [collection] to
   * a specified [value]
   */
  protected suspend fun updateField(collection: MongoCollection<JsonObject>,
      id: String, field: String, value: Any?) {
    collection.updateOneAwait(json {
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
   * Set a [field] of a document with a given [id] and an [expectedValue]
   * in the given [collection] to a specified [newValue]
   */
  protected suspend fun updateField(collection: MongoCollection<JsonObject>,
      id: String, field: String, expectedValue: Any?, newValue: Any?) {
    collection.updateOneAwait(json {
      obj(
          INTERNAL_ID to id,
          field to expectedValue
      )
    }, json {
      obj(
          if (newValue != null) {
            "\$set" to obj(
                field to newValue
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
   * Convert a Java [Instant] to a BSON timestamp
   */
  protected fun instantToTimestamp(instant: Instant?): JsonObject? {
    if (instant == null) {
      return null
    }
    return jsonObjectOf(JsonObjectCodec.TIMESTAMP_FIELD to jsonObjectOf(
        JsonObjectCodec.TIMESTAMP_TIME_FIELD to instant.epochSecond,
        JsonObjectCodec.TIMESTAMP_INCREMENT_FIELD to instant.nano
    ))
  }

  /**
   * Convert a BSON [timestamp] to a Java [Instant]
   */
  protected fun timestampToInstant(timestamp: Any?): Instant? {
    if (timestamp == null) {
      return null
    }
    if (timestamp is String) {
      // backwards compatibility
      return Instant.from(ISO_INSTANT.parse(timestamp))
    }
    val o = timestamp as JsonObject
    val tsf = o.getJsonObject(JsonObjectCodec.TIMESTAMP_FIELD)
    val t = tsf.getLong(JsonObjectCodec.TIMESTAMP_TIME_FIELD)
    val i = tsf.getLong(JsonObjectCodec.TIMESTAMP_INCREMENT_FIELD)
    return Instant.ofEpochSecond(t, i)
  }
}
