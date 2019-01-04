import model.metadata.Cardinality
import model.metadata.Service
import model.metadata.ServiceParameter
import model.processchain.Argument

/**
 * Common service metadata for all tests
 * @author Michel Kraemer
 */
object TestMetadata {
  private val serviceCp = Service("cp", "cp", "Copy", "cp", Service.Runtime.OTHER, listOf(
      ServiceParameter("input_file", "Input file", "Input file",
          Argument.Type.INPUT, Cardinality(1, 1)),
      ServiceParameter("output_file", "Output file", "Output file",
          Argument.Type.OUTPUT, Cardinality(1, 1))
  ))

  private val serviceJoin = Service("join", "join", "Join", "join.sh", Service.Runtime.OTHER, listOf(
      ServiceParameter("i", "Input files", "Many inputs files",
          Argument.Type.INPUT, Cardinality(1, Int.MAX_VALUE)),
      ServiceParameter("o", "Output file", "Single output file",
          Argument.Type.OUTPUT, Cardinality(1, 1))
  ))

  private val serviceSplit = Service("split", "split", "Split", "split.sh", Service.Runtime.OTHER, listOf(
      ServiceParameter("input", "Input file", "An input file",
          Argument.Type.INPUT, Cardinality(1, 1)),
      ServiceParameter("output", "Output files", "Multiple output files",
          Argument.Type.OUTPUT, Cardinality(1, Int.MAX_VALUE))
  ))

  val services = listOf(serviceCp, serviceJoin, serviceSplit)
}
