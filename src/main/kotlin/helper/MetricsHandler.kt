package helper

import io.micrometer.prometheus.PrometheusMeterRegistry
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.common.TextFormat
import io.vertx.core.Handler
import io.vertx.core.buffer.Buffer
import io.vertx.ext.web.RoutingContext
import io.vertx.micrometer.backends.BackendRegistries
import java.io.IOException
import java.io.Writer

/**
 * Prometheus metrics handler for Vert.x Web
 */
class MetricsHandler : Handler<RoutingContext> {
  private val prometheusRegistry: CollectorRegistry = CollectorRegistry.defaultRegistry
  private val micrometerRegistry = BackendRegistries.getDefaultNow() as? PrometheusMeterRegistry

  /**
   * Wrap a [Buffer] as a [Writer] so it can be used with [TextFormat] writer
   */
  private class BufferWriter : Writer() {
    val buffer: Buffer = Buffer.buffer()

    override fun write(cbuf: CharArray, off: Int, len: Int) {
      buffer.appendString(String(cbuf, off, len))
    }

    override fun flush() {
      // do nothing here
    }

    override fun close() {
      // do nothing here
    }
  }

  override fun handle(ctx: RoutingContext) {
    try {
      val writer = BufferWriter()
      val contentType = TextFormat.chooseContentType(ctx.request().getHeader("Accept"))

      // add prometheus metrics
      val filter = ctx.request().params().getAll("name[]").toSet()
      TextFormat.writeFormat(contentType, writer,
          prometheusRegistry.filteredMetricFamilySamples(filter))

      // add micrometer metrics
      if (micrometerRegistry != null) {
        writer.write(micrometerRegistry.scrape(contentType, filter))
      }

      ctx.response()
          .setStatusCode(200)
          .putHeader("content-type", contentType)
          .end(writer.buffer)
    } catch (e: IOException) {
      ctx.fail(e)
    }
  }
}
