import Alert from "../../components/Alert"
import Examples from "../../components/search/Examples"
import Label from "../../components/Label"
import Link from "next/link"
import Page from "../../components/layouts/Page"
import Results from "../../components/search/Results"
import fetcher from "../../components/lib/json-fetcher"
import { hasAnyTypeExpression, hasTypeExpression,
    removeAllTypeExpressions } from "../../components/lib/search-query"
import { Search as SearchIcon } from "react-feather"
import { useEffect, useRef, useState } from "react"
import { useRouter } from "next/router"
import { useSWRConfig } from "swr"
import classNames from "classnames"
import useSWRImmutable from "swr/immutable"
import styles from "./index.scss"

function formatCount(c) {
  if (c >= 1000) {
    let s = Math.floor(c / 1000) + "K"
    if (c / 1000 > Math.floor(c / 1000)) {
      s += "+"
    }
    return s
  }
  return c
}

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

  function makeKey(params) {
    return `${process.env.baseUrl}/search?${params.toString()}`
  }
  function makeExactCountKey(params) {
    return `${process.env.baseUrl}/search?${params.toString()}&size=0&count=exact`
  }

  let key = makeKey(params)
  let countsKey = makeExactCountKey(params)

  const { cache } = useSWRConfig()
  const { data: results, error, isValidating: loading, mutate } = useSWRImmutable(key, async (url) => {
    if (!router.query.q) {
      return undefined
    }
    return fetcher(url)
  }, {
    dedupingInterval: 100
  })
  const { data: counts, error: countError } = useSWRImmutable(() => {
    if (!results) {
      return false
    }
    return countsKey
  }, async (url) => {
    if (results.counts.workflow >= 1000 || results.counts.processChain >= 1000) {
      // estimates are good enough
      return results.counts
    }
    // fetch exact values
    return (await fetcher(url)).counts
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
        cache.delete(countsKey)
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
        let newKey = makeKey(params)
        let newCountKey = makeExactCountKey(params)
        cache.delete(newCountKey)
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
    body = <Alert error>{error.message}</Alert>
  } else if (countError?.message) {
    body = <Alert error>{countError.message}</Alert>
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
        <div className="search-body-container">
          {body}
          {results && results.results.length > 0 && <div className="sidebar">
            <ul>
              <li className={classNames({ active: !hasAnyTypeExpression(router.query.q) })}>
                <div><Link href={`/search?q=${encodeURIComponent(
                  removeAllTypeExpressions(router.query.q))}`}><a>All</a></Link></div>
                {counts && <div className="label">
                  <Label small>{formatCount(counts.total)}</Label></div>}
              </li>
              <li className={classNames({ active: hasTypeExpression(router.query.q, "workflow") })}>
                <div><Link href={`/search?q=${encodeURIComponent(
                  removeAllTypeExpressions(router.query.q) + " is:workflow")}`}><a>Workflows</a></Link></div>
                {counts && <div className="label">
                  <Label small>{formatCount(counts.workflow)}</Label></div>}
              </li>
              <li className={classNames({ active: hasTypeExpression(router.query.q, "processchain") })}>
                <div><Link href={`/search?q=${encodeURIComponent(
                  removeAllTypeExpressions(router.query.q) + " is:processchain")}`}><a>Process Chains</a></Link></div>
                {counts && <div className="label">
                  <Label small>{formatCount(counts.processChain)}</Label></div>}
              </li>
            </ul>
          </div>}
        </div>
      </div>
      <style jsx>{styles}</style>
    </Page>
  )
}

export default Search
