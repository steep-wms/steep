package db

import io.vertx.core.json.JsonArray
import io.vertx.ext.sql.SQLConnection
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.ext.sql.queryWithParamsAwait
import io.vertx.kotlin.ext.sql.updateWithParamsAwait

/**
 * A mutable map that is able to persist its contents to a PostgreSQL database.
 * @param name the map's name
 * @param connection the SQL connection to persist to
 * @param keySerialize a function that serializes keys
 * @param keyDeserialize a function that deserializes keys
 * @param serialize a function that serializes map values
 * @param deserialize a function that deserializes map values
 * @author Michel Kraemer
 */
class PostgreSQLPersistentMap<K, V>(
    private val name: String,
    private val connection: SQLConnection,
    private val keySerialize: (K) -> String,
    private val keyDeserialize: (String) -> K,
    private val serialize: (V) -> String,
    private val deserialize: (String) -> V
) : PersistentMapAdapter<K, V>() {
  companion object {
    /**
     * Table and column names
     */
    private const val PERSISTENTMAP = "persistentmap"
    private const val NAME = "name"
    private const val KEY = "k"
    private const val VALUE = "v"
    private const val PERSISTENTMAP_NAME_KEY = "persistentmap_name_k"
  }

  /**
   * A list of SQL statements and their parameters that should be executed when
   * [persist] is called
   */
  private val log = mutableListOf<Pair<String, JsonArray>>()

  override fun clear() {
    super.clear()
    val params = json {
      array(name)
    }
    log.add(Pair("DELETE FROM $PERSISTENTMAP WHERE $NAME=?", params))
  }

  override fun put(key: K, value: V): V? {
    val r = super.put(key, value)
    val v = serialize(value)
    val params = json {
      array(name, keySerialize(key), v, v)
    }
    log.add(Pair("INSERT INTO $PERSISTENTMAP ($NAME, $KEY, $VALUE) VALUES (?, ?, ?) " +
        "ON CONFLICT ON CONSTRAINT $PERSISTENTMAP_NAME_KEY DO UPDATE SET $VALUE=?", params))
    return r
  }

  override fun putAll(from: Map<out K, V>) {
    from.forEach { k, v -> put(k, v) }
  }

  override fun remove(key: K): V? {
    val r = super.remove(key)
    val params = json {
      array(name, key)
    }
    log.add(Pair("DELETE FROM $PERSISTENTMAP WHERE $NAME=? AND $KEY=?", params))
    return r
  }

  override suspend fun load() {
    super.clear()
    log.clear()

    val params = json {
      array(name)
    }
    val statement = "SELECT $KEY, $VALUE FROM $PERSISTENTMAP WHERE $NAME=?"
    val rs = connection.queryWithParamsAwait(statement, params)
    rs.results.map {
      super.put(keyDeserialize(it.getString(0)), deserialize(it.getString(1)))
    }
  }

  override suspend fun persist() {
    for (p in log) {
      connection.updateWithParamsAwait(p.first, p.second)
    }
    log.clear()
  }
}
