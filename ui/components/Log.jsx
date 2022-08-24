import Tooltip from "./Tooltip"
import classNames from "classnames"
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react"
import { ChevronsDown } from "lucide-react"
import AutoSizer from "react-virtualized-auto-sizer"
import { FixedSizeList } from "react-window"
import styles from "./Log.scss"

const Log = ({ children = [], onLoadMore }) => {
  const preRef = useRef()
  const listRef = useRef()
  const listOuterRef = useRef()
  const scrollLockedAtEnd = useRef(false)
  const [followButtonVisible, setFollowButtonVisible] = useState(false)
  const [itemSize, setItemSize] = useState(30)

  const scrollToEnd = useCallback(() => {
    if (listRef.current) {
      listRef.current.scrollToItem(children.length)
    }
  }, [children])

  const onFollowClick = useCallback(() => {
    scrollToEnd()
  }, [scrollToEnd])

  useEffect(() => {
    onResize()
  }, [])

  // synchronously scroll to end immediately after adding a new line
  useLayoutEffect(() => {
    if (scrollLockedAtEnd.current) {
      scrollToEnd()
    }
  }, [scrollToEnd])

  function onResize() {
    if (preRef.current !== undefined) {
      setItemSize(parseInt(window.getComputedStyle(preRef.current).lineHeight))
    }
  }

  function onScroll({ scrollOffset }) {
    if (listOuterRef.current) {
      let endOffset = listOuterRef.current.scrollHeight - listOuterRef.current.clientHeight - 5
      let atEnd = endOffset <= scrollOffset
      scrollLockedAtEnd.current = atEnd
      setFollowButtonVisible(!atEnd)

      if (children.length > 0 && scrollOffset < 5 && onLoadMore) {
        let oldScrollPosition = listOuterRef.current.scrollHeight
        onLoadMore(() => {
          listRef.current.scrollTo(listOuterRef.current.scrollHeight - oldScrollPosition)
        })
      }
    }
  }

  const Line = ({ index, style }) => {
    let line = children[index].value
    let dashIndex = line.indexOf(" - ")
    let timestamp
    let dash
    let rest
    if (dashIndex >= 0) {
      timestamp = line.substring(0, dashIndex)
      dash = line.substring(dashIndex, dashIndex + 3)
      rest = line.substring(dashIndex + 3)
    } else {
      rest = line
    }
    if (!rest.endsWith("\n")) {
      rest += "\n"
    }

    return <div style={style}><span className="hljs-number">{timestamp}</span>
      <span className="hljs-subst">{dash}</span>{rest}</div>
  }

  return (<>
    <pre className="pre" ref={preRef}>
      <code className="code">
        <AutoSizer onResize={onResize}>
          {({ height, width }) => (
            <FixedSizeList height={height} width={width} ref={listRef} outerRef={listOuterRef}
                itemCount={children.length} itemSize={itemSize} onScroll={onScroll}
                overscanCount={20} itemKey={(i) => children[i].key}>
              {Line}
            </FixedSizeList>
          )}
        </AutoSizer>
      </code>
    </pre>
    <div className={classNames("follow-button", { visible: followButtonVisible })} onClick={onFollowClick}>
      <Tooltip title="Follow">
        <ChevronsDown className="feather" />
      </Tooltip>
    </div>
    <style jsx>{styles}</style>
  </>)
}

export default Log
