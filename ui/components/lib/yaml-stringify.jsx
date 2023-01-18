import { stringify as str } from "json2yaml"

function stringify(json) {
  let yamlStr = str(json)
  yamlStr = yamlStr.replace(/^---$/m, "")
  yamlStr = yamlStr.replace(/^\s\s/gm, "")
  yamlStr = yamlStr.replace(/^(\s*)-\s+/gm, "$1- ")
  yamlStr = yamlStr.trim()
  return yamlStr
}

export default stringify
