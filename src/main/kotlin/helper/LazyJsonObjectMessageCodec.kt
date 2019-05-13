package helper

import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.MessageCodec
import io.vertx.core.json.JsonObject

/**
 * A message codec that lazily calls a function and encodes the returned
 * [JsonObject].
 * @author Michel Kraemer
 */
class LazyJsonObjectMessageCodec : MessageCodec<() -> JsonObject, JsonObject> {
  override fun encodeToWire(buffer: Buffer, s: () -> JsonObject) {
    val encoded = s().toBuffer()
    buffer.appendInt(encoded.length())
    buffer.appendBuffer(encoded)
  }

  override fun decodeFromWire(pos: Int, buffer: Buffer): JsonObject {
    val length = buffer.getInt(pos)
    val npos = pos + 4
    return JsonObject(buffer.slice(npos, npos + length))
  }

  override fun transform(s: () -> JsonObject): JsonObject = s()

  override fun name(): String = "lazyjsonobject"

  override fun systemCodecID(): Byte = -1
}
