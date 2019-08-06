package helper

import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

/**
 * Default implementation of [Subscriber] requesting as many objects as possible
 * @author Michel Kraemer
 */
open class DefaultSubscriber<T> : Subscriber<T> {
  override fun onComplete() {
  }

  override fun onSubscribe(s: Subscription) {
    s.request(Long.MAX_VALUE)
  }

  override fun onNext(t: T) {
  }

  override fun onError(t: Throwable) {
  }
}
