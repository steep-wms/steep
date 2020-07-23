package db.migration

import com.fasterxml.jackson.module.kotlin.readValue
import helper.JsonUtils
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * Removes deprecated properties from workflows and process chains
 * @author Michel Kraemer
 */
class V9__Remove_Arguments_And_Parameters : BaseJavaMigration() {
  companion object {
    private val log = LoggerFactory.getLogger(V9__Remove_Arguments_And_Parameters::class.java)
  }

  /**
   * Migrate process chains - replace argument type 'argument' with 'input'
   */
  private fun migrateProcessChains(connection: Connection) {
    var updated = 0

    val updateStatement = connection.prepareStatement(
        "UPDATE processchains SET data=?::jsonb WHERE id=?")
    val statement = connection.createStatement()
    val resultSet = statement.executeQuery("SELECT id, data FROM processchains")
    while (resultSet.next()) {
      val id = resultSet.getString(1)
      val obj = JsonUtils.mapper.readValue<Map<String, Any>>(resultSet.getString(2))

      val changed = processChainArgumentsToInputs(obj)

      if (changed) {
        updateStatement.setString(1, JsonUtils.mapper.writeValueAsString(obj))
        updateStatement.setString(2, id)
        updated += updateStatement.executeUpdate()
      }
    }

    log.info("$updated process chains updated")
  }

  /**
   * Migrate workflows - merge `parameters` of execute actions into their `inputs`
   */
  private fun migrateWorkflows(connection: Connection) {
    var updated = 0

    val updateStatement = connection.prepareStatement(
        "UPDATE submissions SET data=?::jsonb WHERE id=?")
    val statement = connection.createStatement()
    val resultSet = statement.executeQuery("SELECT id, data FROM submissions")
    while (resultSet.next()) {
      val id = resultSet.getString(1)
      val obj = JsonUtils.mapper.readValue<Map<String, Any>>(resultSet.getString(2))
      val workflow = obj["workflow"]
      if (workflow is Map<*, *>) {
        val changed = removeExecuteActionParameters(workflow)

        if (changed) {
          updateStatement.setString(1, JsonUtils.mapper.writeValueAsString(obj))
          updateStatement.setString(2, id)
          updated += updateStatement.executeUpdate()
        }
      }
    }

    log.info("$updated submissions updated")
  }

  override fun migrate(context: Context) {
    val connection = context.connection
    migrateProcessChains(connection)
    migrateWorkflows(connection)
  }
}
