import { forwardRef, useEffect, useRef } from "react"
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

  useEffect(() => {
    hljs.highlightBlock(ref.current)
  }, [ref])

  return (
    <pre className={classNames("pre", { "has-tab": hasTab })}>
      <code lang={lang} ref={ref} className={classNames("code", { [`language-${lang}`]: lang })}>{children}</code>
      <style jsx>{styles}</style>
    </pre>
  )
})

export default Code
