package db

import de.flapdoodle.embed.mongo.MongodExecutable
import de.flapdoodle.embed.mongo.MongodProcess
import de.flapdoodle.embed.mongo.MongodStarter
import de.flapdoodle.embed.mongo.config.MongodConfigBuilder
import de.flapdoodle.embed.mongo.distribution.Version
import io.vertx.core.Vertx
import io.vertx.ext.mongo.MongoClient
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.kotlin.ext.mongo.runCommandAwait
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll

/**
 * Tests for [MongoDBSubmissionRegistry]
 * @author Michel Kraemer
 */
class MongoDBSubmissionRegistryTest : SubmissionRegistryTest() {
  companion object {
    private val STARTER = MongodStarter.getDefaultInstance()

    private lateinit var MONGOD_EXE: MongodExecutable
    private lateinit var MONGOD: MongodProcess
    private val MONGOD_CONFIG = MongodConfigBuilder()
        .version(Version.Main.PRODUCTION)
        .build()
    private val CONNECTION_STRING = "mongodb://" +
        MONGOD_CONFIG.net().serverAddress.hostAddress + ":" +
        MONGOD_CONFIG.net().port + "/jobmanager"

    @BeforeAll
    @JvmStatic
    @Suppress("UNUSED")
    fun startUp() {
      MONGOD_EXE = STARTER.prepare(MONGOD_CONFIG)
      MONGOD = MONGOD_EXE.start()
    }

    @AfterAll
    @JvmStatic
    @Suppress("UNUSED")
    fun shutdown() {
      MONGOD.stop()
      MONGOD_EXE.stop()
    }
  }

  @AfterEach
  override fun tearDown(vertx: Vertx, ctx: VertxTestContext) {
    val config = json {
      obj(
          "connection_string" to CONNECTION_STRING
      )
    }
    val client = MongoClient.createShared(vertx, config)

    GlobalScope.launch(vertx.dispatcher()) {
      client.runCommandAwait("dropDatabase", json {
        obj(
            "dropDatabase" to 1
        )
      })
      client.close()
      super.tearDown(vertx, ctx)
    }
  }

  override fun createRegistry(vertx: Vertx) = MongoDBSubmissionRegistry(
      vertx, CONNECTION_STRING)
}
