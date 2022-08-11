import classNames from "classnames"
import styles from "./CodeBox.scss"
import Tooltip from "./Tooltip"
import React, { forwardRef, memo, useEffect, useRef, useState } from "react"
import stringify from "./lib/yaml-stringify"
import Clipboard from "clipboard"
import { Clipboard as ClipboardIcon } from "react-feather"
import { toH } from "hast-to-hyperscript"
import { VariableSizeList, areEqual } from "react-window"
import { useRouter } from "next/router"

const COPY = "Copy to clipboard"
const COPIED = "Copied!"
const BLOCK_SIZE = 1000

const Row = memo(({ data, index, style }) => {
  let item = data[index]
  let block = item.block
  if (typeof block === "object") {
    block = toH(React.createElement, block)
  }
  return <div style={style}>{block}</div>
}, areEqual)

const List = forwardRef(({ language, codeLineHeight, blocks }, ref) => {
  return <pre><code lang={language} className={`hljs language-${language}`} ref={ref}><VariableSizeList
    height={codeLineHeight * Math.min(blocks.blocks[0].lines, 100.5)}
    itemCount={blocks.blocks.length} itemData={blocks.blocks}
    estimatedItemSize={codeLineHeight * BLOCK_SIZE} width="100%"
    itemSize={(index) => codeLineHeight * blocks.blocks[index].lines}>{Row}</VariableSizeList></code></pre>
})

function splitIntoBlocks(str) {
  let lines = 0
  let blocks = []
  let i = -1
  let li = 0
  do {
    i = str.indexOf("\n", i + 1)
    if (i >= 0) {
      lines++
      if (lines === BLOCK_SIZE) {
        blocks.push({
          block: str.substring(li, i + 1),
          lines: lines
        })
        li = i + 1
        lines = 0
      }
    } else {
      if (li < str.length) {
        lines++
        blocks.push({
          block: str.substring(li, str.length),
          lines
        })
      }
    }
  } while (i >= 0)

  return blocks
}

const CodeBox = ({ json, yaml = undefined }) => {
  const jsonRef = useRef()
  const yamlRef = useRef()
  const copyBtnRef = useRef()
  const [yamlBlocks, setYamlBlocks] = useState(() => {
    // this should only be done once, so we do it in a state initializer
    let str = yaml?.trim() || stringify(json)
    let blocks = splitIntoBlocks(str)
    return {
      str,
      blocks
    }
  })
  const [jsonBlocks, setJsonBlocks] = useState(() => {
    // this should only be done once, so we do it in a state initializer
    let str = JSON.stringify(json, undefined, 2)
    let blocks = splitIntoBlocks(str)
    return {
      str,
      blocks
    }
  })
  const [copyTooltipVisible, setCopyTooltipVisible] = useState(false)
  const [copyTooltipTitle, setCopyTooltipTitle] = useState(COPY)
  const [activeLang, setActiveLang] = useState(localStorage.activeCodeLanguage || "yaml")
  const [codeLineHeight, setCodeLineHeight] = useState(28)
  const router = useRouter()

  function highlight(code, language, updateBlocks) {
    let worker = new Worker(new URL("./lib/highlight-worker.js", import.meta.url))
    let count = 0
    worker.onmessage = ({ data: { hast, finished } }) => {
      if (hast !== undefined) {
        updateBlocks((state) => {
          let newBlocks = [...state.blocks]
          newBlocks[count] = { ...newBlocks[count], block: hast }
          ++count
          return {
            str: state.str,
            blocks: newBlocks
          }
        })
      }
    }
    worker.postMessage({ code, language, blockSize: BLOCK_SIZE })

    return worker
  }

  useEffect(() => {
    setCodeLineHeight(parseFloat(getComputedStyle(yamlRef.current).lineHeight))
  }, [])

  useEffect(() => {
    let yamlWorker = highlight(yamlBlocks.str, "yaml", setYamlBlocks)
    let jsonWorker = highlight(jsonBlocks.str, "json", setJsonBlocks)

    function handleRouteChange() {
      // abort highlighting if user wants to visit another page
      yamlWorker.terminate()
      jsonWorker.terminate()
    }

    router.events.on("routeChangeStart", handleRouteChange)

    return () => {
      router.events.off("routeChangeStart", handleRouteChange)
    }
  }, [yamlBlocks.str, jsonBlocks.str, router])

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
          <List language="yaml" codeLineHeight={codeLineHeight} blocks={yamlBlocks}
            ref={yamlRef} />
        </div>
        <div className={classNames("code-box-tab", { active: activeLang === "json" })}>
          <List language="json" codeLineHeight={codeLineHeight} blocks={jsonBlocks}
            ref={jsonRef} />
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
