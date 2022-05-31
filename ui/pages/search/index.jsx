import Alert from "../../components/Alert"
import Examples from "../../components/search/Examples"
import Label from "../../components/Label"
import Page from "../../components/layouts/Page"
import Pagination from "../../components/Pagination"
import QuickSearch from "../../components/QuickSearch"
import Results from "../../components/search/Results"
import fetcher from "../../components/lib/json-fetcher"
import { hasAnyTypeExpression, hasTypeExpression,
    removeAllTypeExpressions, hasLocator, toggleLocator } from "../../components/lib/search-query"
import { ArrowRight, Check } from "react-feather"
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
  const tz = Intl.DateTimeFormat().resolvedOptions().timeZone

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
    return `${process.env.baseUrl}/search?${params.toString()}&timeZone=${tz}`
  }
  function makeExactCountKey(params) {
    return `${process.env.baseUrl}/search?${params.toString()}&size=0&count=exact&timeZone=${tz}`
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

  function pushQuery(newInputValue) {
    let params = new URLSearchParams()
    let query
    if (newInputValue) {
      query = "q=" + newInputValue
      params.append("q", newInputValue)
    } else {
      query = undefined
    }
    let newKey = makeKey(params)
    let newCountKey = makeExactCountKey(params)
    cache.delete(newCountKey)
    cache.delete(newKey)
    if (router.query.q === newInputValue && !pageOffset) {
      mutate()
    } else {
      router.push({
        pathname: router.pathname,
        query
      })
    }
  }

  function onInputEnter() {
    if (router.query.q === inputValue && !pageOffset) {
      cache.delete(countsKey)
      cache.delete(key)
      mutate()
    } else {
      pushQuery(inputValue)
    }
  }

  function onChangeOffset(offset) {
    const newParams = new URLSearchParams([...Array.from(params.entries())])
    if (offset > 0) {
      params.append("offset", offset)
    } else {
      params.delete("offset")
    }
    let newKey = makeKey(newParams)
    let newCountKey = makeExactCountKey(newParams)
    cache.delete(newCountKey)
    cache.delete(newKey)
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
          <QuickSearch searchIcon={true} onEscape={() => {}}
            onEnter={() => onInputEnter()} onChange={v => setInputValue(v)}
            initialValue={router.query.q} ref={inputRef} />
        </div>
        <div className="search-body-container">
          {body}
          {results && <div className="sidebar">
            <ul>
              <li className={classNames({ active: !hasAnyTypeExpression(router.query.q) })}
                  onClick={() => pushQuery(removeAllTypeExpressions(router.query.q))}>
                <div className="active-icon"><ArrowRight size="1rem"/></div>
                <div className="name">All</div>
                {counts && <div className="label">
                  <Label small>{formatCount(counts.total)}</Label></div>}
              </li>
              <li className={classNames({ active: hasTypeExpression(router.query.q, "workflow") })}
                  onClick={() => pushQuery(removeAllTypeExpressions(router.query.q) + " is:workflow")}>
                <div className="active-icon"><ArrowRight size="1rem"/></div>
                <div className="name">Workflows</div>
                {counts && <div className="label">
                  <Label small>{formatCount(counts.workflow)}</Label></div>}
              </li>
              <li className={classNames({ active: hasTypeExpression(router.query.q, "processchain") })}
                  onClick={() => pushQuery(removeAllTypeExpressions(router.query.q) + " is:processchain")}>
                <div className="active-icon"><ArrowRight size="1rem"/></div>
                <div className="name">Process Chains</div>
                {counts && <div className="label">
                  <Label small>{formatCount(counts.processChain)}</Label></div>}
              </li>
            </ul>
            <ul>
              <li className="heading">Search in:</li>
              <li className={classNames({ active: hasLocator(router.query.q, "id") })}
                  onClick={() => pushQuery(toggleLocator(router.query.q, "id"))}>
                <div className="active-icon"><Check size="0.85rem"/></div>
                <div className="name">ID</div>
              </li>
              <li className={classNames({ active: hasLocator(router.query.q, "name") })}
                  onClick={() => pushQuery(toggleLocator(router.query.q, "name"))}>
                <div className="active-icon"><Check size="0.85rem"/></div>
                <div className="name">Name</div>
              </li>
              <li className={classNames({ active: hasLocator(router.query.q, "status") })}
                  onClick={() => pushQuery(toggleLocator(router.query.q, "status"))}>
                <div className="active-icon"><Check size="0.85rem"/></div>
                <div className="name">Status</div>
              </li>
              <li className={classNames({ active: hasLocator(router.query.q, "source") })}
                  onClick={() => pushQuery(toggleLocator(router.query.q, "source"))}>
                <div className="active-icon"><Check size="0.85rem"/></div>
                <div className="name">Source</div>
              </li>
              <li className={classNames({ active: hasLocator(router.query.q, "rcs") })}
                  onClick={() => pushQuery(toggleLocator(router.query.q, "rcs"))}>
                <div className="active-icon"><Check size="0.85rem"/></div>
                <div className="name">Required Capabilities</div>
              </li>
              <li className={classNames({ active: hasLocator(router.query.q, "error") })}
                  onClick={() => pushQuery(toggleLocator(router.query.q, "error"))}>
                <div className="active-icon"><Check size="0.85rem"/></div>
                <div className="name">Error Message</div>
              </li>
              <li className={classNames({ active: hasLocator(router.query.q, "start") })}
                  onClick={() => pushQuery(toggleLocator(router.query.q, "start"))}>
                <div className="active-icon"><Check size="0.85rem"/></div>
                <div className="name">Start Time</div>
              </li>
              <li className={classNames({ active: hasLocator(router.query.q, "end") })}
                  onClick={() => pushQuery(toggleLocator(router.query.q, "end"))}>
                <div className="active-icon"><Check size="0.85rem"/></div>
                <div className="name">End Time</div>
              </li>
            </ul>
          </div>}
          {counts && counts.total > 0 && (
            <div className="pagination">
              <Pagination pageSize={pageSize} pageOffset={pageOffset}
                pageTotal={Math.min(1000, counts.total)}
                onChangeOffset={onChangeOffset} />
            </div>
          )}
        </div>
      </div>
      <style jsx>{styles}</style>
    </Page>
  )
}

export default Search
