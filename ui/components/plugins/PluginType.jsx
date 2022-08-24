const PluginType = ({ type }) => {
  let actualType
  switch (type) {
    case "initializer":
      actualType = "Initializer"
      break

    case "outputAdapter":
      actualType = "Output adapter"
      break

    case "processChainAdapter":
      actualType = "Process chain adapter"
      break

    case "processChainConsistencyChecker":
      actualType = "Process chain consistency checker"
      break

    case "progressEstimator":
      actualType = "Progress estimator"
      break

    case "runtime":
      actualType = "Runtime"
      break

    default:
      actualType = type
      break
  }

  return <>{actualType}</>
}

export default PluginType
