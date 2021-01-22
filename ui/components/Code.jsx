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

  // store already highlighted children in an array
  let highlightedCode = useRef([])

  if (children !== undefined) {
    let carr = children
    if (!Array.isArray(carr)) {
      carr = [carr]
    }

    // highlight new children
    for (let i = highlightedCode.current.length; i < carr.length; ++i) {
      let v = hljs.highlight(lang, carr[i]).value
      highlightedCode.current[i] = <span key={i} dangerouslySetInnerHTML={{ __html: v }} />
    }
  }

  return (
    <pre className={classNames("pre", { "has-tab": hasTab })}>
      <code lang={lang} ref={ref} className={classNames("code",
        { [`language-${lang}`]: lang })}>{highlightedCode.current}</code>
      <style jsx>{styles}</style>
    </pre>
  )
})

export default Code
