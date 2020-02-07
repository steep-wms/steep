package helper

import com.mongodb.bulk.BulkWriteResult
import com.mongodb.client.gridfs.model.GridFSFile
import com.mongodb.client.model.CountOptions
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.WriteModel
import com.mongodb.client.result.UpdateResult
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import com.mongodb.reactivestreams.client.gridfs.GridFSBucket
import com.mongodb.reactivestreams.client.gridfs.GridFSDownloadStream
import com.mongodb.reactivestreams.client.gridfs.GridFSUploadStream
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bson.BsonValue
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import io.vertx.ext.mongo.impl.JsonObjectBsonAdapter as wrap

/**
 * A subscriber that requests exactly one object and returns `null` if
 * no object was published
 * @author Michel Kraemer
 */
private class OneSubscriber<T>(private val cont: CancellableContinuation<T?>) : Subscriber<T> {
  private var resumed = false

  override fun onComplete() {
    if (!resumed) {
      cont.resume(null)
      resumed = true
    }
  }

  override fun onSubscribe(s: Subscription) {
    s.request(1)
  }

  override fun onNext(t: T?) {
    if (!resumed) {
      cont.resume(t)
      resumed = true
    }
  }

  override fun onError(t: Throwable) {
    if (!resumed) {
      cont.resumeWithException(t)
      resumed = true
    }
  }
}

/**
 * A subscriber that requests as many objects as possible and collects them
 * in a list
 * @author Michel Kraemer
 */
private class CollectionSubscriber<T>(private val cont: CancellableContinuation<List<T>>) : Subscriber<T> {
  private val result = mutableListOf<T>()

  override fun onComplete() {
    cont.resume(result)
  }

  override fun onSubscribe(s: Subscription) {
    s.request(Long.MAX_VALUE)
  }

  override fun onNext(t: T) {
    result.add(t)
  }

  override fun onError(t: Throwable) {
    cont.resumeWithException(t)
  }
}

/**
 * Converts a function [f] returning a [Publisher] to a coroutine
 */
private suspend fun <T> wrapCoroutine(f: () -> Publisher<T>): T? {
  return suspendCancellableCoroutine { cont: CancellableContinuation<T?> ->
    f().subscribe(OneSubscriber(cont))
  }
}

suspend fun <T> MongoCollection<T>.bulkWriteAwait(requests: List<WriteModel<T>>): BulkWriteResult {
  return wrapCoroutine {
    bulkWrite(requests)
  } ?: throw IllegalStateException("Bulk write did not produce a result")
}

suspend fun <T> MongoCollection<T>.countDocumentsAwait(filter: JsonObject,
    limit: Int = -1): Long {
  return wrapCoroutine {
    val options = CountOptions()
    if (limit >= 0) {
      options.limit(limit)
    }
    countDocuments(wrap(filter), options)
  } ?: 0
}

suspend fun MongoDatabase.dropAwait() {
  wrapCoroutine { drop() }
}

suspend fun <T> MongoCollection<T>.findAwait(filter: JsonObject, limit: Int = -1,
    skip: Int = 0, sort: JsonObject? = null, projection: JsonObject? = null): List<T> {
  return suspendCancellableCoroutine { cont: CancellableContinuation<List<T>> ->
    var f = find(wrap(filter))
    if (limit >= 0) {
      f = f.limit(limit)
    }
    if (skip > 0) {
      f = f.skip(skip)
    }
    if (sort != null) {
      f = f.sort(wrap(sort))
    }
    if (projection != null) {
      f = f.projection(wrap(projection))
    }
    f.subscribe(CollectionSubscriber(cont))
  }
}

suspend fun <T> MongoCollection<T>.findOneAwait(filter: JsonObject,
    projection: JsonObject): T? {
  return wrapCoroutine {
    find(wrap(filter)).projection(wrap(projection)).first()
  }
}

suspend fun <T> MongoCollection<T>.findOneAndUpdateAwait(filter: JsonObject,
    update: JsonObject, options: FindOneAndUpdateOptions): T? {
  return wrapCoroutine {
    findOneAndUpdate(wrap(filter), wrap(update), options)
  }
}

suspend fun <T, R> MongoCollection<T>.distinctAwait(fieldName: String,
    filter: JsonObject, resultClass: Class<R>): List<R> {
  return suspendCancellableCoroutine { cont: CancellableContinuation<List<R>> ->
    val d = distinct(fieldName, wrap(filter), resultClass)
    d.subscribe(CollectionSubscriber(cont))
  }
}

suspend fun <T> MongoCollection<T>.insertOneAwait(document: T) {
  wrapCoroutine {
    insertOne(document)
  }
}

suspend fun <T> MongoCollection<T>.updateOneAwait(filter: JsonObject,
    update: JsonObject): UpdateResult {
  return wrapCoroutine {
    updateOne(wrap(filter), wrap(update))
  } ?: throw IllegalStateException("Update operation did not produce a result")
}

suspend fun <T> MongoCollection<T>.updateManyAwait(filter: JsonObject,
    update: JsonObject): UpdateResult {
  return wrapCoroutine {
    updateMany(wrap(filter), wrap(update))
  } ?: throw IllegalStateException("Update operation did not produce a result")
}

suspend fun GridFSBucket.findAwait(filter: JsonObject): GridFSFile? {
  return wrapCoroutine {
    find(wrap(filter)).first()
  }
}

suspend fun GridFSBucket.deleteAwait(id: BsonValue) {
  wrapCoroutine {
    delete(id)
  }
}

suspend fun GridFSUploadStream.writeAwait(src: ByteBuffer): Int {
  return wrapCoroutine {
    write(src)
  } ?: throw IllegalStateException("Write operation did not produce a result")
}

suspend fun GridFSUploadStream.closeAwait() {
  wrapCoroutine {
    close()
  }
}

suspend fun GridFSDownloadStream.readAwait(dst: ByteBuffer): Int {
  return wrapCoroutine {
    read(dst)
  } ?: throw IllegalStateException("Write operation did not produce a result")
}

suspend fun GridFSDownloadStream.closeAwait() {
  wrapCoroutine {
    close()
  }
}
