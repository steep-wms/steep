import classNames from "classnames"
import "./CodeBox.scss"
import Tooltip from "./Tooltip"
import { useEffect, useRef, useState } from "react"
import { stringify } from "json2yaml"
import Clipboard from "clipboard"
import { Clipboard as ClipboardIcon } from "react-feather"

import hljs from "highlight.js/lib/core"
import json from "highlight.js/lib/languages/json"
import yaml from "highlight.js/lib/languages/yaml"
hljs.registerLanguage("json", json)
hljs.registerLanguage("yaml", yaml)

const COPY = "Copy to clipboard"
const COPIED = "Copied!"

export default ({ json }) => {
  const jsonRef = useRef()
  const yamlRef = useRef()
  const copyBtnRef = useRef()
  const [copyTooltipVisible, setCopyTooltipVisible] = useState(false)
  const [copyTooltipTitle, setCopyTooltipTitle] = useState(COPY)
  const [activeLang, setActiveLang] = useState(localStorage.activeCodeLanguage || "yaml")

  let str = JSON.stringify(json, undefined, 2)
  str = str.replace()

  let yamlStr = stringify(json)
  yamlStr = yamlStr.replace(/^---$/m, "")
  yamlStr = yamlStr.replace(/^\s\s/mg, "")
  yamlStr = yamlStr.replace(/^(\s*)-\s+/mg, "$1- ")
  yamlStr = yamlStr.trim()

  useEffect(() => {
    hljs.highlightBlock(jsonRef.current)
    hljs.highlightBlock(yamlRef.current)
  }, [])

  useEffect(() => {
    let clipboardYaml = new Clipboard(copyBtnRef.current, {
      target: () => activeLang === "yaml" ? yamlRef.current : jsonRef.current
    })
    clipboardYaml.on("success", e => {
      e.clearSelection()
      setCopyTooltipTitle(COPIED)
    })

    return () => {
      clipboardYaml.destroy()
    }
  }, [activeLang])

  function onClickLanguage(lang) {
    localStorage.activeCodeLanguage = lang
    setActiveLang(lang)
  }

  function onCopyBtnMouseLeave() {
    setCopyTooltipVisible(false)
    setCopyTooltipTitle(COPY)
  }

  return (
    <div className="code-box">
      <div className="code-box-title">
        <div className={classNames("code-box-title-tab", { active: activeLang === "yaml" })}
          onClick={() => onClickLanguage("yaml")}>YAML</div>
        <div className={classNames("code-box-title-tab", { active: activeLang === "json" })}
          onClick={() => onClickLanguage("json")}>JSON</div>
      </div>
      <div className="code-box-main">
        <div className={classNames("code-box-tab", { active: activeLang === "yaml" })}>
          <pre><code lang="yaml" ref={yamlRef}>{yamlStr}</code></pre>
        </div>
        <div className={classNames("code-box-tab", { active: activeLang === "json" })}>
          <pre><code lang="json" ref={jsonRef}>{str}</code></pre>
        </div>
        <Tooltip title={copyTooltipTitle} forceVisible={copyTooltipVisible}
            className="code-box-copy-btn" onShow={() => setCopyTooltipVisible(true)}
            onHide={onCopyBtnMouseLeave}>
          <span ref={copyBtnRef}>
            <ClipboardIcon className="feather" />
          </span>
        </Tooltip>
      </div>
    </div>
  )
}
