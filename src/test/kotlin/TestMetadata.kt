import model.metadata.Cardinality
import model.metadata.RuntimeArgument
import model.metadata.Service
import model.metadata.ServiceParameter
import model.processchain.Argument

/**
 * Common service metadata for all tests
 * @author Michel Kraemer
 */
object TestMetadata {
  private val serviceCp = Service("cp", "cp", "Copy", "cp", Service.RUNTIME_OTHER, listOf(
      ServiceParameter("input_file", "Input file", "Input file",
          Argument.Type.INPUT, Cardinality(1, 1)),
      ServiceParameter("output_file", "Output file", "Output file",
          Argument.Type.OUTPUT, Cardinality(1, 1))
  ))

  private val serviceCpCustom = Service("cp_custom", "cp_custom", "Copy", "cp",
      Service.RUNTIME_OTHER, listOf(
          ServiceParameter("input_file", "Input file", "Input file",
              Argument.Type.INPUT, Cardinality(1, 1)),
          ServiceParameter("output_file", "Output file", "Output file",
              Argument.Type.OUTPUT, Cardinality(1, 1), "custom")
      )
  )

  private val serviceCpDefaultParam = Service("cpDefaultParam", "cp", "Copy", "cp",
      Service.RUNTIME_OTHER, listOf(
      ServiceParameter("no_overwrite", "No overwrite", "Do not overwrite existing file",
          Argument.Type.INPUT, Cardinality(1, 1), Argument.DATA_TYPE_BOOLEAN,
          true, label = "-n"),
      ServiceParameter("input_file", "input file", "input file",
          Argument.Type.INPUT, Cardinality(1, 1)),
      ServiceParameter("output_file", "output file", "output file",
          Argument.Type.OUTPUT, Cardinality(1, 1))
  ))

  private val serviceJoin = Service("join", "join", "Join", "join.sh", Service.RUNTIME_OTHER, listOf(
      ServiceParameter("i", "Input files", "Many inputs files",
          Argument.Type.INPUT, Cardinality(1, Int.MAX_VALUE)),
      ServiceParameter("o", "Output file", "Single output file",
          Argument.Type.OUTPUT, Cardinality(1, 1))
  ))

  private val serviceSplit = Service("split", "split", "Split", "split.sh", Service.RUNTIME_OTHER, listOf(
      ServiceParameter("input", "Input file", "An input file",
          Argument.Type.INPUT, Cardinality(1, 1)),
      ServiceParameter("output", "Output files", "Multiple output files",
          Argument.Type.OUTPUT, Cardinality(1, Int.MAX_VALUE))
  ))

  private val serviceWithDocker = Service("serviceWithDocker", "Service With Docker",
      "A service that requires docker", "service:latest", Service.RUNTIME_DOCKER, listOf(
      ServiceParameter("input", "Input file", "An input file",
          Argument.Type.INPUT, Cardinality(1, 1)),
      ServiceParameter("output", "Output file", "An output file",
          Argument.Type.OUTPUT, Cardinality(1, 1))
  ))

  private val serviceWithRuntimeArgs = Service(
      id = "serviceWithRuntimeArgs",
      name = "Service With Runtime Arguments",
      description = "A service that requires runtime arguments",
      path = "service:latest",
      runtime = Service.RUNTIME_DOCKER,
      parameters = listOf(
        ServiceParameter("input", "Input file", "An input file",
            Argument.Type.INPUT, Cardinality(1, 1)),
        ServiceParameter("output", "Output file", "An output file",
            Argument.Type.OUTPUT, Cardinality(1, 1))
      ),
      runtimeArgs = listOf(
          RuntimeArgument("removeContainer", "Remove container", "Remove container when it exits",
              dataType = Argument.DATA_TYPE_BOOLEAN, label = "--rm", value = "true"),
          RuntimeArgument("dataMount", "Data mount", "Mount data directory",
              label = "-v", value = "/data:/data")
      )
  )

  private val serviceSplitToDir = Service(
      id = "splitToDir",
      name = "splitToDir",
      description = "Split to directory",
      path = "splitToDir.sh",
      runtime = Service.RUNTIME_OTHER,
      parameters = listOf(
        ServiceParameter("i", "Input file", "A single input file",
            Argument.Type.INPUT, Cardinality(1, 1)),
        ServiceParameter("o", "Output directory", "Output directory",
            Argument.Type.OUTPUT, Cardinality(1, 1), Argument.DATA_TYPE_DIRECTORY)
      )
  )

  private val serviceJoinFromDir = Service(
      id = "joinFromDir",
      name = "joinFromDir",
      description = "Join files from directory",
      path = "joinFromDir.sh",
      runtime = Service.RUNTIME_OTHER,
      parameters = listOf(
          ServiceParameter("i", "Input directory", "Input directory",
              Argument.Type.INPUT, Cardinality(1, 1), Argument.DATA_TYPE_DIRECTORY),
          ServiceParameter("f", "Input files", "An optional list of input files",
              Argument.Type.INPUT, Cardinality(0, Int.MAX_VALUE)),
          ServiceParameter("o", "Output file", "Output file",
              Argument.Type.OUTPUT, Cardinality(1, 1))
      )
  )

  val services = listOf(serviceCp, serviceCpCustom, serviceCpDefaultParam,
      serviceJoin, serviceSplit, serviceWithDocker, serviceWithRuntimeArgs,
      serviceSplitToDir, serviceJoinFromDir)
}
