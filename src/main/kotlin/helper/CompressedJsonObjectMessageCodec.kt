package helper

import com.fasterxml.jackson.core.type.TypeReference
import io.airlift.compress.zstd.ZstdCompressor
import io.airlift.compress.zstd.ZstdDecompressor
import io.prometheus.client.Counter
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.MessageCodec
import io.vertx.core.json.JsonObject

/**
 * Vert.x event bus message codec that compresses JSON objects with Zstd
 * @author Michel Kraemer
 */
class CompressedJsonObjectMessageCodec : MessageCodec<JsonObject, JsonObject> {
  companion object {
    const val NAME = "compressedjsonobject"

    private const val COMPRESSION_NONE: Byte = 0
    private const val COMPRESSION_ZSTD: Byte = 1

    private val counterTotalSent = Counter.build()
        .name("steep_eventbus_compressed_json_total_sent")
        .help("Total number of sent compressed JSON messages")
        .register()
    private val counterTotalRecv = Counter.build()
        .name("steep_eventbus_compressed_json_total_recv")
        .help("Total number of received compressed JSON messages")
        .register()
    private val counterBytesWritten = Counter.build()
        .name("steep_eventbus_compressed_json_bytes_written")
        .help("Total number of written compressed JSON bytes")
        .register()
    private val counterBytesWrittenBefore = Counter.build()
        .name("steep_eventbus_compressed_json_bytes_written_before")
        .help("Total number of JSON bytes before compression")
        .register()
    private val counterBytesRead = Counter.build()
        .name("steep_eventbus_compressed_json_bytes_read")
        .help("Total number of read compressed JSON bytes")
        .register()
    private val counterBytesReadAfter = Counter.build()
        .name("steep_eventbus_compressed_json_bytes_read_after")
        .help("Total number of JSON bytes after decompression")
        .register()
    private val counterBytesTimeCompress = Counter.build()
        .name("steep_eventbus_compressed_json_time_compress")
        .help("Total number of milliseconds spent compressing JSON")
        .register()
    private val counterBytesTimeDecompress = Counter.build()
        .name("steep_eventbus_compressed_json_time_decompress")
        .help("Total number of milliseconds spent decompressing JSON")
        .register()
  }

  override fun encodeToWire(buffer: Buffer, s: JsonObject) {
    val uncompressed = JsonUtils.mapper.writeValueAsBytes(s)

    if (uncompressed.size < 512) {
      // don't compress very small messages to save CPU cycles
      buffer.appendByte(COMPRESSION_NONE)
      buffer.appendInt(uncompressed.size)
      buffer.appendBytes(uncompressed)
    } else {
      val start = System.nanoTime()

      buffer.appendByte(COMPRESSION_ZSTD)
      val compressor = ZstdCompressor()
      val maxCompressedLength = compressor.maxCompressedLength(uncompressed.size)
      val dst = ByteArray(maxCompressedLength)
      val compressedLength = compressor.compress(uncompressed, 0, uncompressed.size,
          dst, 0, maxCompressedLength)
      buffer.appendInt(compressedLength)
      buffer.appendBytes(dst, 0, compressedLength)

      counterBytesTimeCompress.inc((System.nanoTime() - start).toDouble() / 1e6)
      counterTotalSent.inc()
      counterBytesWritten.inc(compressedLength.toDouble())
      counterBytesWrittenBefore.inc(uncompressed.size.toDouble())
    }
  }

  override fun decodeFromWire(pos: Int, buffer: Buffer): JsonObject {
    val compression = buffer.getByte(pos)
    var np = pos + 1
    val length = buffer.getInt(np)
    np += 4
    val compressed = buffer.getBytes(np, np + length)

    val tr = object : TypeReference<Map<String, Any>>() {}
    return if (compression == COMPRESSION_NONE) {
      JsonObject(JsonUtils.mapper.readValue(compressed, tr))
    } else {
      val start = System.nanoTime()

      val decompressor = ZstdDecompressor()
      val decompressedSize = ZstdDecompressor.getDecompressedSize(compressed, 0, compressed.size)
      val dst = ByteArray(decompressedSize.toInt())
      val decompressedLength = decompressor.decompress(compressed, 0, compressed.size,
          dst, 0, dst.size)

      counterBytesTimeDecompress.inc((System.nanoTime() - start).toDouble() / 1e6)
      counterTotalRecv.inc()
      counterBytesRead.inc(length.toDouble())
      counterBytesReadAfter.inc(decompressedLength.toDouble())

      JsonObject(JsonUtils.mapper.readValue(dst, 0, decompressedLength, tr))
    }
  }

  override fun transform(s: JsonObject): JsonObject = s.copy()

  override fun name(): String = NAME

  override fun systemCodecID(): Byte = -1
}
