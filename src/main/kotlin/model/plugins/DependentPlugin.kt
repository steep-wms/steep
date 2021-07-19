package model.plugins

/**
 * A plugin that depends on one or more other plugins to be executed first
 * @author Michel Kraemer
 */
interface DependentPlugin : Plugin {
  /**
   * A list of the names of the plugins on which this plugin depends
   */
  val dependsOn: List<String>
}
