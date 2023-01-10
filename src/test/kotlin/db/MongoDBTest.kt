package db

import com.mongodb.ConnectionString
import com.mongodb.reactivestreams.client.MongoClients
import de.flapdoodle.embed.mongo.distribution.Version
import de.flapdoodle.embed.mongo.transitions.Mongod
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess
import de.flapdoodle.reverse.TransitionWalker
import helper.deleteAllAwait
import helper.listCollectionNamesAwait
import io.vertx.core.Vertx
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll

/**
 * Common code for all tests that need a MongoDB instance
 * @author MicheL Kraemer
 */
interface MongoDBTest {
  companion object {
    private var mongod: TransitionWalker.ReachedState<RunningMongodProcess>? = null

    @JvmStatic
    val connectionString: String get() = "mongodb://" +
        mongod!!.current().serverAddress.host + ":" +
        mongod!!.current().serverAddress.port + "/steep"

    @BeforeAll
    @JvmStatic
    @Suppress("UNUSED")
    fun startUp() {
      mongod = Mongod.instance().start(Version.V4_4_16)
    }

    @AfterAll
    @JvmStatic
    @Suppress("UNUSED")
    fun shutdown() {
      mongod!!.close()
    }
  }

  @AfterEach
  fun tearDownDatabase(vertx: Vertx, ctx: VertxTestContext) {
    val cs = ConnectionString(connectionString)
    val client = MongoClients.create(cs)

    CoroutineScope(vertx.dispatcher()).launch {
      // Don't drop the whole database - just delete the contents of all
      // collections instead. It makes the unit tests much faster (they now take
      // a few seconds vs several minutes) because the collections do not have
      // to be recreated all the time.
      // client.getDatabase(cs.database).dropAwait()
      val db = client.getDatabase(cs.database)
      val collectionNames = db.listCollectionNamesAwait()
      for (name in collectionNames) {
        val coll = db.getCollection(name)
        coll.deleteAllAwait()
      }

      client.close()
      ctx.completeNow()
    }
  }
}
