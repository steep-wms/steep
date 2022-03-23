import classNames from "classnames"
import styles from "./CodeBox.scss"
import Tooltip from "./Tooltip"
import { useEffect, useRef, useState } from "react"
import stringify from "./lib/yaml-stringify"
import Clipboard from "clipboard"
import { Clipboard as ClipboardIcon } from "react-feather"

import hljs from "highlight.js/lib/core"
import json from "highlight.js/lib/languages/json"
import yaml from "highlight.js/lib/languages/yaml"
hljs.registerLanguage("json", json)
hljs.registerLanguage("yaml", yaml)

const COPY = "Copy to clipboard"
const COPIED = "Copied!"

let worker = undefined
if (typeof window !== "undefined") {
  worker = new Worker(new URL("./lib/highlight-worker.js", import.meta.url))
}

const CodeBox = ({ json }) => {
  const jsonRef = useRef()
  const yamlRef = useRef()
  const copyBtnRef = useRef()
  const [highlightedJson, setHighlightedJson] = useState()
  const [highlightedYaml, setHighlightedYaml] = useState()
  const [copyTooltipVisible, setCopyTooltipVisible] = useState(false)
  const [copyTooltipTitle, setCopyTooltipTitle] = useState(COPY)
  const [activeLang, setActiveLang] = useState(localStorage.activeCodeLanguage || "yaml")
  const [highlightingTooLong, setHighlightingTooLong] = useState(false)

  let str = JSON.stringify(json, undefined, 2)
  let yamlStr = stringify(json)

  useEffect(() => {
    let highlightingFinished = false
    worker.onmessage = ({ data: { html } }) => {
      setHighlightedYaml(html)
      worker.onmessage = ({ data: { html } }) => {
        setHighlightedJson(html)
        highlightingFinished = true
      }
      worker.postMessage({ code: str, language: "json" })
    }
    worker.postMessage({ code: yamlStr, language: "yaml" })

    setTimeout(() => {
      if (!highlightingFinished) {
        setHighlightingTooLong(true)
        worker.terminate()
      }
    }, 2000)
  }, [str, yamlStr])

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
        {highlightingTooLong && <div className="highlighting-disabled">
          Syntax highlighting was disabled because it took too long
        </div>}
      </div>
      <div className="code-box-main">
        <div className={classNames("code-box-tab", { active: activeLang === "yaml" })}>
          {highlightedYaml && (
            <pre><code lang="json" className="hljs language-yaml" ref={yamlRef}
              dangerouslySetInnerHTML={{ __html: highlightedYaml }}></code></pre>
          ) || (
            <pre><code lang="json" className="hljs language-yaml" ref={yamlRef}>{yamlStr}</code></pre>
          )}
        </div>
        <div className={classNames("code-box-tab", { active: activeLang === "json" })}>
          {highlightedJson && (
            <pre><code lang="json" className="hljs language-json" ref={jsonRef}
              dangerouslySetInnerHTML={{ __html: highlightedJson }}></code></pre>
          ) || (
            <pre><code lang="json" className="hljs language-json" ref={jsonRef}>{str}</code></pre>
          )}
        </div>
        <span className="code-box-copy-btn">
          <Tooltip title={copyTooltipTitle} forceVisible={copyTooltipVisible}
              onShow={() => setCopyTooltipVisible(true)}
              onHide={onCopyBtnMouseLeave}>
            <span ref={copyBtnRef}>
              <ClipboardIcon className="feather" />
            </span>
          </Tooltip>
        </span>
      </div>
      <style jsx>{styles}</style>
    </div>
  )
}

export default CodeBox
