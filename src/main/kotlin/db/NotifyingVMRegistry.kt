package db

import io.vertx.core.Vertx

/**
 * Wraps around a VM registry and published events whenever the
 * registry's contents have changed.
 * @author Michel Kraemer
 */
class NotifyingVMRegistry(private val delegate: VMRegistry, private val vertx: Vertx) :
    VMRegistry by delegate {

}
