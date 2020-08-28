package db.migration

import org.flywaydb.core.api.migration.Context

/**
 * Migrate process chains again to fix a bug in 5.4.0 (i.e. the type of
 * `runtimeArgs` was not renamed correctly)
 * @author Michel Kraemer
 */
class V10__Remove_Arguments : V9__Remove_Arguments_And_Parameters() {
  override fun migrate(context: Context) {
    val connection = context.connection
    migrateProcessChains(connection)
  }
}
