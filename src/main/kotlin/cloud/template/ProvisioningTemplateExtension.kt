package cloud.template

import cloud.SSHClient
import io.pebbletemplates.pebble.extension.AbstractExtension
import io.pebbletemplates.pebble.extension.Function

/**
 * An extension for the template engine that the [cloud.CloudManager] uses for
 * provisioning scripts
 * @author Michel Kraemer
 */
class ProvisioningTemplateExtension(private val sshClient: SSHClient) : AbstractExtension() {
  override fun getFunctions(): MutableMap<String, Function> {
    return mutableMapOf(
        "readFile" to ReadFileFunction(),
        "upload" to UploadFunction(sshClient)
    )
  }
}
