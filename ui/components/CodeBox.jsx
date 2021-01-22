import classNames from "classnames"
import styles from "./CodeBox.scss"
import Code from "./Code"
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
  const copyBtnRef = useRef()
  const [copyTooltipVisible, setCopyTooltipVisible] = useState(false)
  const [copyTooltipTitle, setCopyTooltipTitle] = useState(COPY)
  const [activeLang, setActiveLang] = useState(localStorage.activeCodeLanguage || "yaml")

  let str = JSON.stringify(json, undefined, 2)
  str = str.replace()

  let yamlStr = stringify(json)

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
          <Code lang="yaml" ref={yamlRef} hasTab>{yamlStr}</Code>
        </div>
        <div className={classNames("code-box-tab", { active: activeLang === "json" })}>
          <Code lang="json" ref={jsonRef} hasTab>{str}</Code>
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
