import stringify from "./yaml-stringify"
import yaml from "js-yaml"

function tryParseJsonObject(source) {
  source = source.trim()
  if (!source[0] === "{") {
    return undefined
  }
  try {
    return JSON.parse(source)
  } catch (e) {
    return undefined
  }
}

function submissionToSource(submission) {
  if (!submission.source) {
    return {
      json: submission.workflow,
      yaml: stringify(submission.workflow)
    }
  }

  let parsedJson = tryParseJsonObject(submission.source)
  if (parsedJson !== undefined) {
    return {
      json: parsedJson,
      yaml: stringify(parsedJson)
    }
  }

  return {
    json: yaml.load(submission.source),
    yaml: submission.source
  }
}

export default submissionToSource
