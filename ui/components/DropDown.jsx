import classNames from "classnames"
import { ChevronDown } from "lucide-react"
import { useEffect, useRef, useState } from "react"
import styles from "./DropDown.scss"

const DropDown = ({ title, right, primary, small, forceTitleVisible, children }) => {
  const [visible, setVisible] = useState(false)
  const ref = useRef()
  const btnRef = useRef()

  function onDropDownClick() {
    // Let the click propagate to the parent element first before we make
    // the drop down menu visible. This makes sure other drop down menus on the
    // page are closed. If we'd call setVisible without setTimeout here, our
    // menu would never be displayed because the onDocumentClick handler above
    // would just hide it again.
    setTimeout(() => {
      if (visible) {
        setVisible(false)
        btnRef.current.blur()
      } else {
        setVisible(true)
        btnRef.current.focus()
      }
    }, 0)
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
      <button className={classNames("dropdown-btn", { primary, small })} ref={btnRef}
          onClick={onDropDownClick}>
        <span className={classNames("dropdown-text", { "force-visible": forceTitleVisible })}>{title} </span><ChevronDown />
       </button>
      <div className={classNames("dropdown-menu", { visible, right, "force-title-visible": forceTitleVisible })}>{children}</div>
      <style jsx>{styles}</style>
    </div>
  )
}

export default DropDown
