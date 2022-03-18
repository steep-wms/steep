import model.processchain.Argument

fun dummyOutputAdapterWithoutUnusedParameters(output: Argument): List<String> {
  return listOf("${output.variable.value}1", "${output.variable.value}2")
}
