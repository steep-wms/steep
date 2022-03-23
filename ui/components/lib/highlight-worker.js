import hljs from "highlight.js/lib/core"
import json from "highlight.js/lib/languages/json"
import yaml from "highlight.js/lib/languages/yaml"
hljs.registerLanguage("json", json)
hljs.registerLanguage("yaml", yaml)

self.onmessage = ({ data: { code, language } }) => {
  let html = hljs.highlight(code, { language }).value
  self.postMessage({ html })
}
