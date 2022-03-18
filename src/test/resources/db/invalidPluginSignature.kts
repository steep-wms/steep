import model.processchain.Executable

fun invalidPluginSignature(executables: List<Executable>) {
  throw IllegalStateException("THIS IS NOT THE EXCEPTION WE SHOULD GET")
}
