import Alert from "../../components/Alert"
import Examples from "../../components/search/Examples"
import Page from "../../components/layouts/Page"
import Results from "../../components/search/Results"
import fetcher from "../../components/lib/json-fetcher"
import { Search as SearchIcon } from "react-feather"
import { useEffect, useRef, useState } from "react"
import { useRouter } from "next/router"
import { useSWRConfig } from "swr"
import useSWRImmutable from "swr/immutable"
import styles from "./index.scss"

const Search = () => {
  const router = useRouter()
  const [inputValue, setInputValue] = useState("")
  const inputRef = useRef()

  let pageOffset = router.query.offset || undefined
  if (pageOffset !== undefined) {
    pageOffset = Math.max(0, parseInt(pageOffset))
  }
  let pageSize = router.query.size || undefined
  if (pageSize !== undefined) {
    pageSize = Math.max(0, parseInt(pageSize))
  }

  let params = new URLSearchParams()
  if (router.query.q) {
    params.append("q", router.query.q)
    if (pageOffset !== undefined) {
      params.append("offset", pageOffset)
    }
    if (pageSize !== undefined) {
      params.append("size", pageSize)
    }
  }
  let key = `${process.env.baseUrl}/search?${params.toString()}`

  const { cache } = useSWRConfig()
  const { data: results, error, isValidating: loading, mutate } = useSWRImmutable(key, async (url) => {
    if (!router.query.q) {
      return undefined
    }
    return fetcher(url)
  }, {
    dedupingInterval: 100
  })

  useEffect(() => {
    setInputValue(router.query.q || "")
    if (router.query.q) {
      inputRef.current.blur()
    } else {
      inputRef.current.focus()
    }
  }, [router.query.q])

  function onInputKeyDown(e) {
    if (e.keyCode === 13) {
      if (router.query.q === inputValue) {
        cache.delete(key)
        mutate()
      } else {
        let params = new URLSearchParams()
        let query
        if (inputValue) {
          query = "q=" + inputValue
          params.append("q", inputValue)
        } else {
          query = undefined
        }
        let newKey = `${process.env.baseUrl}/search?${params.toString()}`
        cache.delete(newKey)
        router.push({
          pathname: router.pathname,
          query
        })
      }
    }
  }

  let body
  if (error?.message) {
    body = <Alert error>{error?.message}</Alert>
  } else if (results) {
    body = <Results results={results} />
  } else if (!loading) {
    body = <Examples />
  }

  return (
    <Page title="Search">
      <div className="search-container">
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
