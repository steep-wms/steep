import styles from "./QuickSearch.scss"
import { forwardRef, useCallback, useEffect, useImperativeHandle, useRef, useState } from "react"
import { useRouter } from "next/router"
import { addTypeExpression } from "./lib/search-query"
import { Search, XCircle } from "lucide-react"
import classNames from "classnames"

const QuickSearch = forwardRef(({ type, searchIcon = false, onEnter, onEscape,
    onChange, initialValue = "" }, ref) => {
  const innerRef = useRef()
  useImperativeHandle(ref, () => innerRef.current)

  const [value, setValue] = useState(initialValue)
  const [focus, setFocus] = useState(false)
  const router = useRouter()

  const onDocumentKeyDown = useCallback((e) => {
    if (e.key === "/" && !innerRef.current.matches(":focus")) {
      innerRef.current.focus()
      e.preventDefault()
    }
  }, [innerRef])

  useEffect(() => {
    document.addEventListener("keydown", onDocumentKeyDown)

    return () => {
      document.removeEventListener("keydown", onDocumentKeyDown)
    }
  }, [onDocumentKeyDown])

  useEffect(() => {
    setValue(initialValue)
  }, [initialValue])

  onEnter = onEnter || function () {
    let query
    if (value) {
      query = "q=" + encodeURIComponent(addTypeExpression(value, type))
    } else {
      query = undefined
    }
    router.push({
      pathname: "/search",
      query
    })
  }

  onEscape = onEscape || function () {
    doSetValue("")
    innerRef.current.blur()
  }

  function onInputKeyDown(e) {
    if (e.keyCode === 13) {
      onEnter(e)
    } else if (e.keyCode === 27) {
      onEscape(e)
    }
  }

  function onCancel() {
    doSetValue("")
    setTimeout(() => {
      innerRef.current.focus()
    })
  }

  function doSetValue(newValue) {
    setValue(newValue)
    if (onChange) {
      onChange(newValue)
    }
  }

  function doOnChange(e) {
    doSetValue(e.target.value)
  }

  return (<>
    <div className="quicksearch-container">
      <input type="text" placeholder="Search &hellip;" value={value}
        onChange={e => doOnChange(e)} onKeyDown={onInputKeyDown}
        ref={innerRef} role="search" onFocus={() => setFocus(true)}
        onBlur={() => setFocus(false)} className={classNames({ "has-search-icon": searchIcon })} />
      {searchIcon && <div className="search-icon"><Search /></div>}
      {focus || <div className="slash-icon">/</div>}
      {focus && value && <div className="close-icon"><XCircle size="1rem" onMouseDown={() => onCancel()} /></div>}
    </div>
    <style jsx>{styles}</style>
  </>)
})

export default QuickSearch
