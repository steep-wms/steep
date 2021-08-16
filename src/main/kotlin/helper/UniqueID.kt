package helper

import com.google.common.io.BaseEncoding
import org.bson.types.ObjectId
import java.util.Date

/**
 * Generates short unique identifiers
 * @author Michel Kraemer
 */
object UniqueID : IDGenerator {
  // use a different "epoch" to make IDs shorter
  private const val OFFSET = 1545829231994L

  private val base32 = BaseEncoding.base32().lowerCase().omitPadding()

  /**
   * Generate a unique ID
   * @return the unique ID
   */
  override fun next(): String {
    val o2 = ObjectId(Date(System.currentTimeMillis() - OFFSET))
    return base32.encode(o2.toByteArray())
  }

  /**
   * Extract milliseconds since epoch from a given unique [id]
   */
  fun toMillis(id: String): Long {
    val barr = base32.decode(id)
    val o = ObjectId(barr)
    return o.timestamp * 1000L + OFFSET
  }
}
