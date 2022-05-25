import Alert from "../../components/Alert"
import Examples from "../../components/search/Examples"
import Page from "../../components/layouts/Page"
import Results from "../../components/search/Results"
import fetcher from "../../components/lib/json-fetcher"
import classNames from "classnames"
import { Search as SearchIcon } from "react-feather"
import { useEffect, useRef, useState } from "react"
import { useRouter } from "next/router"
import styles from "./index.scss"

const Search = () => {
  const router = useRouter()
  const [error, setError] = useState()
  const [results, setResults] = useState()
  const [inputValue, setInputValue] = useState("")
  const [forceRefresh, setForceRefresh] = useState(0)
  const [loading, setLoading] = useState(true)
  const inputRef = useRef()

  useEffect(() => {
    let pageOffset = router.query.offset || undefined
    if (pageOffset !== undefined) {
      pageOffset = Math.max(0, parseInt(pageOffset))
    }
    let pageSize = router.query.size || undefined
    if (pageSize !== undefined) {
      pageSize = Math.max(0, parseInt(pageSize))
    }

    setInputValue(router.query.q || "")
    if (router.query.q !== undefined && router.query.q !== "") {
      let params = new URLSearchParams()
      params.append("q", router.query.q)
      if (pageOffset !== undefined) {
        params.append("offset", pageOffset)
      }
      if (pageSize !== undefined) {
        params.append("size", pageSize)
      }
      fetcher(`${process.env.baseUrl}/search?${params.toString()}`).then(response => {
        inputRef.current.blur()
        setError(undefined)
        setResults(response)
        setLoading(false)
      }).catch(error => {
        inputRef.current.focus()
        setError(error.message)
        setResults(undefined)
        setLoading(false)
        console.log(error)
      })
    } else {
      setError(undefined)
      setResults(undefined)
      setLoading(false)
      inputRef.current.focus()
    }
  }, [router.query.q, router.query.size, router.query.offset, forceRefresh])

  function onInputKeyDown(e) {
    if (e.keyCode === 13) {
      if (router.query.q === inputValue) {
        setForceRefresh(forceRefresh + 1)
      } else {
        router.push({
          pathname: router.pathname,
          query: "q=" + inputValue
        })
      }
    }
  }

  let body
  if (error) {
    body = <Alert error>{error}</Alert>
  } else if (results) {
    body = <Results results={results} />
  } else {
    body = <Examples />
  }

  return (
    <Page title="Search">
      <div className={classNames("search-container", { loading })}>
        <div className="search-input-container">
          <div className="search-icon"><SearchIcon /></div>
          <input type="text" placeholder="Search &hellip;"
            value={inputValue} ref={inputRef} onKeyDown={onInputKeyDown}
            onChange={e => setInputValue(e.target.value)}
            role="search"></input>
        </div>
        {body}
      </div>
      <style jsx>{styles}</style>
    </Page>
  )
}

export default Search
