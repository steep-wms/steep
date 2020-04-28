import classNames from "classnames"
import Page from "./Page"
import Breadcrumbs from "../Breadcrumbs"
import "./DetailPage.scss"
import { ChevronDown } from "react-feather"
import { useEffect, useRef, useState } from "react"

export default ({ breadcrumbs, title, subtitle, menu, children }) => {
  let dropdown
  let [menuVisible, setMenuVisible] = useState(false)
  let dropdownRef = useRef()
  let dropdownBtnRef = useRef()

  function onDropDownClick() {
    if (menuVisible) {
      setMenuVisible(false)
      dropdownBtnRef.current.blur()
    } else {
      setMenuVisible(true)
      dropdownBtnRef.current.focus()
    }
  }

  useEffect(() => {
    function onDocumentClick() {
      if (menuVisible) {
        setMenuVisible(false)
      }
    }

    document.addEventListener("click", onDocumentClick)

    return () => {
      document.removeEventListener("click", onDocumentClick)
    }
  }, [menuVisible])

  if (menu) {
    dropdown = (
      <div className="detail-page-title-dropdown" ref={dropdownRef}>
        <button className="detail-page-title-dropdown-btn" ref={dropdownBtnRef} onClick={onDropDownClick}>
          <span className="detail-page-title-dropdown-text">Actions </span><ChevronDown />
         </button>
        <div className={classNames("detail-page-title-menu", { visible: menuVisible })}>{menu}</div>
      </div>
    )
  }

  return (
    <Page title={title}>
      {title && <div className="detail-page-title"><h1 className="no-margin-bottom">{title}</h1>{dropdown}</div>}
      {subtitle && <p className="detail-page-subtitle">{subtitle}</p>}
      {breadcrumbs && <Breadcrumbs breadcrumbs={breadcrumbs} />}
      {(title || subtitle) && <hr className="detail-page-divider" />}
      <div className="detail-page-main">
        {children}
      </div>
    </Page>
  )
}
