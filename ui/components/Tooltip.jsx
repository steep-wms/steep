import classNames from "classnames"
import { createPopper } from "@popperjs/core"
import { useEffect, useRef, useState } from "react"
import styles from "./Tooltip.scss"

const Tooltip = ({ title, delay = 300, forceVisible = undefined, className,
    onShow, onHide, children }) => {
  const targetRef = useRef()
  const tooltipRef = useRef()
  const timer = useRef()
  const [visible, setVisible] = useState(false)
  const [tooltip, setTooltip] = useState()

  if (forceVisible !== undefined) {
    delay = 0
    if (forceVisible !== visible) {
      setVisible(forceVisible)
    }
  }

  useEffect(() => {
    let t = tooltip
    return () => {
      if (t) {
        t.destroy()
      }
    }
  }, [tooltip])

  useEffect(() => {
    if (tooltip) {
      tooltip.update()
    }
  }, [title, tooltip])

  useEffect(() => {
    return () => {
      if (timer.current) {
        clearTimeout(timer.current)
        timer.current = undefined
      }
    }
  }, [])

  function show() {
    function showNow() {
      if (tooltip === undefined) {
        let options = {
          modifiers: [{
            name: "offset",
            options: {
              offset: [0, 8]
            }
          }]
        }

        setTooltip(createPopper(targetRef.current, tooltipRef.current, options))
      }

      setVisible(true)
    }

    onShow && onShow()

    if (delay === 0) {
      showNow()
    } else {
      timer.current = setTimeout(showNow, delay)
    }
  }

  function hide() {
    onHide && onHide()

    if (timer.current) {
      clearTimeout(timer.current)
      timer.current = undefined
    }

    // Destroy tooltip in the next event loop tick after setVisible has been
    // executed. This prevents flickering.
    setTimeout(() => {
      if (tooltip) {
        tooltip.destroy()
        setTooltip(undefined)
      }
    }, 0)

    setVisible(false)
  }

  return (
    <>
      <span ref={targetRef} className={className}
          onMouseEnter={() => show()} onMouseLeave={() => hide()}>
        {children}
      </span>
      <div className={classNames("tooltip", { visible })} ref={tooltipRef}>
        {title}
        <div className="tooltip-arrow" data-popper-arrow></div>
      </div>
      <style jsx>{styles}</style>
    </>
  )
}

export default Tooltip
