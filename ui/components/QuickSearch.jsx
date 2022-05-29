import styles from "./QuickSearch.scss"
import { useEffect, useRef, useState } from "react"
import { useRouter } from "next/router"
import { addTypeExpression } from "./lib/search-query"
import { XCircle } from "react-feather"

const QuickSearch = ({ type }) => {
  const ref = useRef()
  const [value, setValue] = useState("")
  const [focus, setFocus] = useState(false)
  const router = useRouter()

  function onDocumentKeyDown(e) {
    if (e.key === "/" && !ref.current.matches(":focus")) {
      ref.current.focus()
      e.preventDefault()
    }
  }

  useEffect(() => {
    document.addEventListener("keydown", onDocumentKeyDown)

    return () => {
      document.removeEventListener("keydown", onDocumentKeyDown)
    }
  }, [])

  function onInputKeyDown(e) {
    if (e.keyCode === 13) {
      let query
      if (value) {
        query = "q=" + addTypeExpression(value, type)
      } else {
        query = undefined
      }
      router.push({
        pathname: "/search",
        query
      })
    } else if (e.keyCode === 27) {
      setValue("")
      ref.current.blur()
    }
  }

  function onCancel() {
    setValue("")
    setTimeout(() => {
      ref.current.focus()
    })
  }

  return (<>
    <div className="quicksearch-container">
      <input type="text" placeholder="Search &hellip;" value={value}
        onChange={e => setValue(e.target.value)} onKeyDown={onInputKeyDown}
        ref={ref} role="search" onFocus={() => setFocus(true)}
        onBlur={() => setFocus(false)} />
      {focus || <div className="slash-icon">/</div>}
      {focus && value && <div className="close-icon"><XCircle size="1rem" onMouseDown={() => onCancel()} /></div>}
    </div>
    <style jsx>{styles}</style>
  </>)
}

export default QuickSearch
