import classNames from "classnames"
import "./CodeBox.scss"
import { useEffect, useRef, useState } from "react"
import { stringify } from "json2yaml"
import Clipboard from "clipboard"
import { Clipboard as ClipboardIcon } from "react-feather"
import { createPopper } from "@popperjs/core"

import hljs from "highlight.js/lib/highlight"
import json from "highlight.js/lib/languages/json"
import yaml from "highlight.js/lib/languages/yaml"
hljs.registerLanguage("json", json)
hljs.registerLanguage("yaml", yaml)

export default ({ json }) => {
  const jsonRef = useRef()
  const yamlRef = useRef()
  const copyBtnRef = useRef()
  const copyTooltipRef = useRef()
  const copiedTooltipRef = useRef()
  const [copyTooltipVisible, setCopyTooltipVisible] = useState(false)
  const [copiedTooltipVisible, setCopiedTooltipVisible] = useState(false)
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
      setCopyTooltipVisible(false)
      setCopiedTooltipVisible(true)
    })

    return () => {
      clipboardYaml.destroy()
    }
  }, [activeLang])

  useEffect(() => {
    let options = {
      modifiers: [{
        name: "offset",
        options: {
          offset: [0, 8]
        }
      }]
    }

    let copyYamlTooltip = createPopper(copyBtnRef.current, copyTooltipRef.current, options)
    let copiedYamlTooltip = createPopper(copyBtnRef.current, copiedTooltipRef.current, options)

    return () => {
      copiedYamlTooltip.destroy()
      copyYamlTooltip.destroy()
    }
  }, [copyTooltipVisible, copiedTooltipVisible])

  function onClickLanguage(lang) {
    localStorage.activeCodeLanguage = lang
    setActiveLang(lang)
  }

  function onCopyBtnMouseLeave() {
    setCopyTooltipVisible(false)
    setCopiedTooltipVisible(false)
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
        <div className="code-box-copy-btn" ref={copyBtnRef}
            onMouseEnter={() => setCopyTooltipVisible(true)}
            onMouseLeave={() => onCopyBtnMouseLeave()}>
          <ClipboardIcon className="feather" />
        </div>
        <div className={classNames("code-box-tooltip", { visible: copyTooltipVisible })}
            ref={copyTooltipRef}>
          Copy to clipboard
        </div>
        <div className={classNames("code-box-tooltip", { visible: copiedTooltipVisible })}
            ref={copiedTooltipRef}>
          Copied!
        </div>
      </div>
    </div>
  )
}
