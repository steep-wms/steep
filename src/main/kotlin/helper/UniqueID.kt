package helper

import io.seruco.encoding.base62.Base62
import org.bson.types.ObjectId
import java.util.Date

/**
 * Generates short unique identifiers
 * @author Michel Kraemer
 */
object UniqueID {
  private val base62 = Base62.createInstance()

  /**
   * Generate a unique ID
   * @return the unique ID
   */
  fun next(): String {
    // use a different "epoch" to make IDs shorter
    val o2 = ObjectId(Date(System.currentTimeMillis() - 1545829231994L))
    // convert to base62 and remove leading zeros
    return String(base62.encode(o2.toByteArray())).trimStart('0')
  }
}
