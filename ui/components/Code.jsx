import { forwardRef, useRef } from "react"
import styles from "./Code.scss"
import classNames from "classnames"

import hljs from "highlight.js/lib/core"
import json from "highlight.js/lib/languages/json"
import log from "./lib/language-log"
import text from "highlight.js/lib/languages/plaintext"
import yaml from "highlight.js/lib/languages/yaml"
hljs.registerLanguage("json", json)
hljs.registerLanguage("log", log)
hljs.registerLanguage("text", text)
hljs.registerLanguage("yaml", yaml)

const Code = forwardRef(({ lang, hasTab, children }, ref) => {
  let fallbackRef = useRef()
  if (ref === null) {
    ref = fallbackRef
  }

  // store already highlighted children in a map
  let cache = useRef(new Map())

  let highlightedCode = []
  if (children !== undefined) {
    let carr = children
    if (!Array.isArray(carr)) {
      carr = [carr]
    }

    // highlight new children
    for (let i = 0; i < carr.length; ++i) {
      let c = carr[i]
      let key = c.key !== undefined ? c.key : c
      let highlighted = cache.current.get(key)
      if (highlighted === undefined) {
        let value = c.value !== undefined ? c.value : c
        let html = hljs.highlight(lang, value).value
        highlighted = <span key={key} dangerouslySetInnerHTML={{ __html: html }} />
        cache.current.set(key, highlighted)
      }
      highlightedCode.push(highlighted)
    }
  }

  return (
    <pre className={classNames("pre", { "has-tab": hasTab })}>
      <code lang={lang} ref={ref} className={classNames("code",
        { [`language-${lang}`]: lang })}>{highlightedCode}</code>
      <style jsx>{styles}</style>
    </pre>
  )
})

export default Code
