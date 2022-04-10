package helper

import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonArray
import io.vertx.kotlin.core.json.jsonObjectOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Test [CompressedJsonObjectMessageCodec]
 * @author Michel Kraemer
 */
class CompressedJsonObjectMessageCodecTest {
  /**
   * Test serializing and deserializing a very small object that will not be
   * compressed
   */
  @Test
  fun noCompression() {
    val codec = CompressedJsonObjectMessageCodec()

    val buf = Buffer.buffer()
    buf.appendString("foobar")
    val pos = buf.length()

    val obj = jsonObjectOf("foo" to "bar", "name" to "Elvis",
        "junk" to JsonArray(Array(100) { "a" }.toList()))
    codec.encodeToWire(buf, obj)

    // check no-compression marker
    assertThat(buf.getByte(pos)).isEqualTo(0)

    // check that data is not compressed
    assertThat(buf.length()).isGreaterThan(200)

    val obj2 = codec.decodeFromWire(pos, buf)

    assertThat(obj2).isEqualTo(obj)
  }

  /**
   * Test serializing and deserializing a large object that will be compressed
   */
  @Test
  fun compression() {
    val codec = CompressedJsonObjectMessageCodec()

    val buf = Buffer.buffer()
    buf.appendString("foobar")
    val pos = buf.length()

    val obj = jsonObjectOf("foo" to "bar", "name" to "Elvis",
        "junk" to JsonArray(Array(2048) { "a" }.toList()))
    codec.encodeToWire(buf, obj)

    // check compression marker
    assertThat(buf.getByte(pos)).isEqualTo(1)

    // check that data is compressed
    assertThat(buf.length()).isLessThan(200)

    val obj2 = codec.decodeFromWire(pos, buf)

    assertThat(obj2).isEqualTo(obj)
  }

  /**
   * Test serialization and deserialization and mix native and pure-Java implementations
   */
  @Test
  fun nativeVsPureJava() {
    val codecPure = CompressedJsonObjectMessageCodec(forcePureJava = true)
    val codecNative = CompressedJsonObjectMessageCodec(forcePureJava = false)

    // compress pure, decompress native
    val buf = Buffer.buffer()
    buf.appendString("foobar")
    val pos = buf.length()

    val obj = jsonObjectOf("foo" to "bar", "name" to "Elvis",
        "junk" to JsonArray(Array(2048) { "a" }.toList()))
    codecPure.encodeToWire(buf, obj)

    // check compression marker
    assertThat(buf.getByte(pos)).isEqualTo(1)

    // check that data is compressed
    assertThat(buf.length()).isLessThan(200)

    val obj2 = codecNative.decodeFromWire(pos, buf)

    assertThat(obj2).isEqualTo(obj)

    // compress native, decompress pure
    val buf2 = Buffer.buffer()
    codecNative.encodeToWire(buf2, obj)

    // check compression marker
    assertThat(buf2.getByte(0)).isEqualTo(1)

    // check that data is compressed
    assertThat(buf2.length()).isLessThan(200)

    val obj3 = codecPure.decodeFromWire(0, buf2)

    assertThat(obj3).isEqualTo(obj)
  }
}
