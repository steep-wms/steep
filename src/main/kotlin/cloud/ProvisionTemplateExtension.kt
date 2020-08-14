package cloud

import com.mitchellbosecke.pebble.extension.AbstractExtension
import com.mitchellbosecke.pebble.extension.Function

/**
 * An extension for the template engine that the [CloudManager] uses for
 * provisioning scripts
 * @author Michel Kraemer
 */
class ProvisionTemplateExtension : AbstractExtension() {
  override fun getFunctions(): MutableMap<String, Function> {
    return mutableMapOf("readFile" to ReadFileFunction())
  }
}
