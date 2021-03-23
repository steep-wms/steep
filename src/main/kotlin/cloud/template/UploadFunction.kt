package cloud.template

import cloud.SSHClient
import com.mitchellbosecke.pebble.extension.Function
import com.mitchellbosecke.pebble.extension.escaper.SafeString
import com.mitchellbosecke.pebble.template.EvaluationContext
import com.mitchellbosecke.pebble.template.PebbleTemplate
import helper.UniqueID
import helper.glob
import org.apache.commons.io.FilenameUtils

/**
 * A custom function for the template engine that the [cloud.CloudManager] uses
 * for provisioning scripts. This function uploads one or more files to the
 * created virtual machine. Use it as follows:
 *
 *     {{ upload(src, dst) }}
 *
 * For example:
 *
 *     {{ upload("conf/plugins", "/usr/local/steep/conf") }}
 *
 * Paths can be absolute or relative to the current working directory (typically
 * Steep's application directory). Supports glob patterns.
 *
 * @author Michel Kraemer
 */
class UploadFunction(private val sshClient: SSHClient) : Function {
  override fun getArgumentNames(): MutableList<String> {
    return mutableListOf("src", "dst")
  }

  override fun execute(args: MutableMap<String, Any>, self: PebbleTemplate,
      context: EvaluationContext, lineNumber: Int): SafeString {
    val src = args["src"]?.toString() ?: throw IllegalArgumentException(
        "`upload' function requires `src' parameter")

    val dst = args["dst"]?.toString() ?: throw IllegalArgumentException(
        "`upload' function requires `dst' parameter")

    // find files to upload
    val files = glob(src)

    // group files
    val filePairs = mutableListOf<Pair<String, String>>()
    for ((root, l) in files) {
      for (f in l) {
        val dir = FilenameUtils.getPath(f)
        filePairs.add(dir to "$root/$f")
      }
    }
    val groupedFiles = filePairs.groupBy({ it.first }, { it.second })

    // create target directories
    val tmpDest = "/tmp/steep-upload-${UniqueID.next()}"
    val dirsToCreate = groupedFiles.keys.filter { it.isNotEmpty() }
    if (dirsToCreate.isNotEmpty()) {
      sshClient.executeBlocking("""mkdir -p ${dirsToCreate.joinToString(" ") { d ->
        """"$tmpDest/$d"""" }}""")
    }

    // upload files
    for ((dir, l) in groupedFiles) {
      sshClient.uploadFilesBlocking(l, "$tmpDest/$dir")
    }

    // Insert a command into the provisioning script that will eventually
    // move all files from the temporary directory to the final destination
    // Note: we're relying on bash to be installed on the target system. If
    // this should ever become a problem, we need to mimic what we are doing
    // above (i.e. create all target directories and copy the individual
    // files). As an optimisation, we could check whether bash exists or not.
    return SafeString("""bash -c 'shopt -s dotglob; mv "$tmpDest/"* "$dst"'""")
  }
}
