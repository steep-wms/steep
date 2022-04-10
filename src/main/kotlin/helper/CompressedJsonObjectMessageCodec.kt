package helper

import com.fasterxml.jackson.core.type.TypeReference
import com.github.luben.zstd.Zstd
import io.airlift.compress.zstd.ZstdCompressor
import io.airlift.compress.zstd.ZstdDecompressor
import io.prometheus.client.Counter
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.MessageCodec
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory
import io.netty.handler.codec.compression.Zstd as NettyZstd

/**
 * Vert.x event bus message codec that compresses JSON objects with Zstd
 * @author Michel Kraemer
 */
class CompressedJsonObjectMessageCodec(private val forcePureJava: Boolean = false) :
    MessageCodec<JsonObject, JsonObject> {
  companion object {
    const val NAME = "compressedjsonobject"
    private val log = LoggerFactory.getLogger(CompressedJsonObjectMessageCodec::class.java)

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

  init {
    if (!NettyZstd.isAvailable()) {
      log.warn("Native Zstandard implementation not available on your " +
          "system. Falling back to pure Java library.")
    }
  }

  private fun compressPureJava(uncompressed: ByteArray, buffer: Buffer): Int {
    val compressor = ZstdCompressor()
    val maxCompressedLength = compressor.maxCompressedLength(uncompressed.size)
    val dst = ByteArray(maxCompressedLength)
    val compressedLength = compressor.compress(uncompressed, 0, uncompressed.size,
        dst, 0, maxCompressedLength)
    buffer.appendInt(compressedLength)
    buffer.appendBytes(dst, 0, compressedLength)
    return compressedLength
  }

  private fun compressNative(uncompressed: ByteArray, buffer: Buffer): Int {
    val maxCompressedLength = Zstd.compressBound(uncompressed.size.toLong())
    val dst = ByteArray(maxCompressedLength.toInt())
    val compressedLength = Zstd.compress(dst, uncompressed, Zstd.defaultCompressionLevel())
    if (Zstd.isError(compressedLength)) {
      throw IllegalStateException("Could not compress message. Error: " +
          "${Zstd.getErrorName(compressedLength)} (${Zstd.getErrorCode(compressedLength)})")
    }
    val r = compressedLength.toInt()
    buffer.appendInt(r)
    buffer.appendBytes(dst, 0, r)
    return r
  }

  private fun compress(uncompressed: ByteArray, buffer: Buffer): Int {
    return if (!forcePureJava && NettyZstd.isAvailable()) {
      compressNative(uncompressed, buffer)
    } else {
      compressPureJava(uncompressed, buffer)
    }
  }

  private fun decompressPureJava(compressed: ByteArray): Pair<ByteArray, Int> {
    val decompressor = ZstdDecompressor()
    val decompressedSize = ZstdDecompressor.getDecompressedSize(compressed, 0, compressed.size)
    val dst = ByteArray(decompressedSize.toInt())
    val decompressedLength = decompressor.decompress(compressed, 0, compressed.size,
        dst, 0, dst.size)
    return dst to decompressedLength
  }

  private fun decompressNative(compressed: ByteArray): Pair<ByteArray, Int> {
    val decompressedSize = Zstd.decompressedSize(compressed, 0, compressed.size)
    val dst = ByteArray(decompressedSize.toInt())
    val decompressedLength = Zstd.decompress(dst, compressed)
    if (Zstd.isError(decompressedLength)) {
      throw IllegalStateException("Could not decompress message. Error: " +
          "${Zstd.getErrorName(decompressedLength)} (${Zstd.getErrorCode(decompressedLength)})")
    }
    return dst to decompressedLength.toInt()
  }

  private fun decompress(compressed: ByteArray): Pair<ByteArray, Int> {
    return if (!forcePureJava && NettyZstd.isAvailable()) {
      decompressNative(compressed)
    } else {
      decompressPureJava(compressed)
    }
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
      val compressedLength = compress(uncompressed, buffer)

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

      val (dst, decompressedLength) = decompress(compressed)

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
