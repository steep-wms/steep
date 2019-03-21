package db

import java.util.Collections

/**
 * A mutable set that is able to persist its contents (backed by a [PersistentMap])
 * @author Michel Kraemer
 */
class PersistentSet<E>(private val delegate: PersistentMap<E, Boolean>) :
    MutableSet<E> by Collections.newSetFromMap(delegate), PersistentCollection {
  /**
   * Load the set contents
   */
  override suspend fun load() {
    delegate.load()
  }

  /**
   * Persist the contents of this set
   */
  override suspend fun persist() {
    delegate.persist()
  }
}
