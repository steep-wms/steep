package cloud

import com.fasterxml.jackson.module.kotlin.readValue
import helper.JsonUtils
import io.vertx.core.buffer.Buffer
import io.vertx.core.shareddata.ClusterSerializable

/**
 * Wraps around [VMCircuitBreaker] so it can be put into a [helper.hazelcast.ClusterMap]
 * @author Michel Kraemer
 */
class VMCircuitBreakerHolder(private var cb: VMCircuitBreaker? = null) : ClusterSerializable {
  fun unsafeGet(): VMCircuitBreaker = cb!!

  override fun writeToBuffer(buffer: Buffer) {
    val b = JsonUtils.mapper.writeValueAsBytes(cb)
    buffer.appendInt(b.size)
    buffer.appendBytes(b)
  }

  override fun readFromBuffer(pos: Int, buffer: Buffer): Int {
    val len = buffer.getInt(pos)
    val b = buffer.getBytes(pos + 4, pos + 4 + len)
    cb = JsonUtils.mapper.readValue<VMCircuitBreaker>(b)
    return pos + 4 + len
  }
}
