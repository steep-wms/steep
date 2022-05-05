import classNames from "classnames"
import { ChevronDown } from "react-feather"
import { useEffect, useRef, useState } from "react"
import styles from "./DropDown.scss"

const DropDown = ({ title, right, primary, children }) => {
  const [visible, setVisible] = useState(false)
  const ref = useRef()
  const btnRef = useRef()

  function onDropDownClick(e) {
    if (visible) {
      setVisible(false)
      btnRef.current.blur()
    } else {
      setVisible(true)
      btnRef.current.focus()
    }
    e.stopPropagation()
  }

  useEffect(() => {
    function onDocumentClick() {
      if (visible) {
        setVisible(false)
      }
    }

    document.addEventListener("click", onDocumentClick)

    return () => {
      document.removeEventListener("click", onDocumentClick)
    }
  }, [visible])

  return (
    <div className="dropdown" ref={ref}>
      <button className={classNames("dropdown-btn", { primary })} ref={btnRef}
          onClick={onDropDownClick}>
        <span className="dropdown-text">{title} </span><ChevronDown />
       </button>
      <div className={classNames("dropdown-menu", { visible, right })}>{children}</div>
      <style jsx>{styles}</style>
    </div>
  )
}

export default DropDown
