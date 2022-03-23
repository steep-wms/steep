import classNames from "classnames"
import styles from "./CodeBox.scss"
import Tooltip from "./Tooltip"
import { useEffect, useRef, useState } from "react"
import stringify from "./lib/yaml-stringify"
import Clipboard from "clipboard"
import { Clipboard as ClipboardIcon } from "react-feather"

const COPY = "Copy to clipboard"
const COPIED = "Copied!"

const CodeBox = ({ json }) => {
  const jsonRef = useRef()
  const yamlRef = useRef()
  const yamlStrRef = useRef()
  const copyBtnRef = useRef()
  const [highlightedJson, setHighlightedJson] = useState()
  const [highlightedYaml, setHighlightedYaml] = useState()
  const [copyTooltipVisible, setCopyTooltipVisible] = useState(false)
  const [copyTooltipTitle, setCopyTooltipTitle] = useState(COPY)
  const [activeLang, setActiveLang] = useState(localStorage.activeCodeLanguage || "yaml")
  const [highlightingTooLongJson, setHighlightingTooLongJson] = useState(false)
  const [highlightingTooLongYaml, setHighlightingTooLongYaml] = useState(false)

  let str = JSON.stringify(json, undefined, 2)
  if (yamlStrRef.current === undefined) {
    yamlStrRef.current = stringify(json)
  }
  let yamlStr = yamlStrRef.current

  function highlight(code, language, success, error) {
    let finished = false
    let worker = new Worker(new URL("./lib/highlight-worker.js", import.meta.url))
    worker.onmessage = ({ data: { html } }) => {
      finished = true
      success(html)
    }
    worker.postMessage({ code, language })

    setTimeout(() => {
      if (!finished) {
        error()
        worker.terminate()
      }
    }, 2000)
  }

  useEffect(() => {
    highlight(yamlStr, "yaml", setHighlightedYaml, () => setHighlightingTooLongYaml(true))
    highlight(str, "json", setHighlightedJson, () => setHighlightingTooLongJson(true))
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
        {highlightingTooLongJson && activeLang === "json" && <div className="highlighting-disabled">
          Syntax highlighting was disabled because it took too long
        </div>}
        {highlightingTooLongYaml && activeLang === "yaml" && <div className="highlighting-disabled">
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
