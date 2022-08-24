import classNames from "classnames"
import Alert from "../Alert"
import Breadcrumbs from "../Breadcrumbs"
import DropDown from "../DropDown"
import Notification from "../Notification"
import Page from "./Page"
import Pagination from "../Pagination"
import QuickSearch from "../QuickSearch"
import { useCallback, useContext, useEffect, useMemo, useState } from "react"
import styles from "./ListPage.scss"
import fetcher from "../lib/json-fetcher"
import { useRouter } from "next/router"
import { Check } from "lucide-react"
import debounce from "lodash/debounce"

function List({ Context, ListItem, subjects, path, pagination, pageSize,
    pageOffset, enabledFilterValues, forceUpdate }) {
  const workflows = useContext(Context.Items)
  const updateItems = useContext(Context.UpdateItems)
  const [error, setError] = useState()
  const [pageTotal, setPageTotal] = useState(0)

  const router = useRouter()
  const initialRender = Object.keys(router.query).length === 0 &&
      typeof window !== "undefined" && !!window.location.search

  const forceReset = useCallback(() => {
    updateItems({ action: "set" })
    setPageTotal(0)
  }, [updateItems])

  let params = new URLSearchParams()
  if (pagination) {
    if (pageOffset !== undefined) {
      params.append("offset", pageOffset)
    }
    if (pageSize !== undefined) {
      params.append("size", pageSize)
    }
  }
  if (enabledFilterValues !== undefined) {
    Object.keys(enabledFilterValues).forEach(k => {
      params.append(k, enabledFilterValues[k])
    })
  }

  let url = `${process.env.baseUrl}/${path}?${params.toString()}`

  useEffect(() => {
    if (initialRender) {
      // skip fetch if router.query hasn't been populated yet
      return
    }

    forceReset()

    fetcher(url, true)
      .then(r => {
        updateItems({ action: "set", items: r.body })
        if (pagination) {
          let pageTotalHeader = r.headers.get("x-page-total")
          if (pageTotalHeader !== null) {
            setPageTotal(+pageTotalHeader)
          }
        }
      })
      .catch(err => {
        console.error(err)
        setError(<Alert error>Could not load {subjects}</Alert>)
      })
  }, [url, subjects, pagination, updateItems, forceReset, forceUpdate, initialRender])

  function reset(newOffset) {
    if ((newOffset || 0) !== (pageOffset || 0)) {
      forceReset()
    }
  }

  let listItems
  if (workflows.items !== undefined) {
    listItems = workflows.items.map(i => <ListItem key={i.id} item={i} />)
  }

  return (<div className={classNames("list-container", { loading: listItems === undefined })}>
    {listItems}
    {listItems && listItems.length === 0 && <>There are no {subjects}.</>}
    {error}
    {pagination && pageTotal + workflows.added > 0 && (
      <div className="pagination">
        <Pagination pageSize={pageSize} pageOffset={pageOffset}
          pageTotal={pageTotal + workflows.added} onChangeOffset={reset} />
      </div>
    )}
    <style jsx>{styles}</style>
  </div>)
}

const ListPage = (props) => {
  let pagination = props.pagination
  if (pagination === undefined) {
    pagination = true
  }

  const router = useRouter()
  const [updatesAvailable, setUpdatesAvailableNow] = useState(false)
  const [forceUpdate, setForceUpdate] = useState(0)

  const setUpdatesAvailable = useMemo(() => debounce((updatesAvailable) => {
    setUpdatesAvailableNow(updatesAvailable)
  }, 100), [setUpdatesAvailableNow])

  let pageOffset
  let pageSize
  if (pagination) {
    pageOffset = router.query.offset || undefined
    if (pageOffset !== undefined) {
      pageOffset = Math.max(0, parseInt(pageOffset))
    }
    pageSize = router.query.size || 10
    pageSize = Math.max(0, parseInt(pageSize))
  }

  let hasEnabledFilters = false
  let enabledFilterValues
  if (props.filters) {
    enabledFilterValues = {}
    for (let f of props.filters) {
      let value = router.query[f.name] || undefined
      if (value !== undefined) {
        enabledFilterValues[f.name] = value
        if (value === f.enabledValue) {
          hasEnabledFilters = true
        }
      }
    }
  }
  let serializedEnabledFilterValues = JSON.stringify(enabledFilterValues)

  useEffect(() => {
    setUpdatesAvailableNow(false)
  }, [serializedEnabledFilterValues])

  useEffect(() => {
    setUpdatesAvailableNow(false)
  }, [pageOffset, pageSize, forceUpdate])

  function shouldAddItem(item) {
    let result = true
    if (enabledFilterValues !== undefined) {
      Object.keys(enabledFilterValues).forEach(k => {
        let v = enabledFilterValues[k]
        result = result && item[k] === v
      })
    }
    if (result && pageOffset > 0) {
      setUpdatesAvailable(true)
      return false
    }
    return result
  }

  function reducer(state, { action, items }, next) {
    if (action === "set") {
      setUpdatesAvailable(false)
    }

    if (!updatesAvailable && action === "update" && state.items !== undefined &&
        enabledFilterValues !== undefined) {
      // Check if any of the items would match the current filter (if there
      // is any) after the update - OR if it would not match anymore. If so,
      // set the updates-available flag.
      let keys = Object.keys(enabledFilterValues)
      if (keys.length > 0) {
        for (let item of items) {
          let oldItem = state.items.find(oi => oi.id === item.id)
          let didMatch = oldItem !== undefined
          let willMatch = true
          keys.forEach(k => {
            let v = enabledFilterValues[k]
            didMatch = didMatch && oldItem[k] === v
            if (k in item) {
              willMatch = willMatch && item[k] === v
            } else {
              if (oldItem !== undefined) {
                willMatch = willMatch && oldItem[k] === v
              } else {
                willMatch = false
              }
            }
          })
          if (didMatch !== willMatch) {
            setUpdatesAvailable(true)
            break
          }
        }
      }
    }

    return next(state, { action, items })
  }

  function toggleFilter(f, enabled) {
    let query = { ...router.query }
    delete query.offset
    if (enabled) {
      delete query[f.name]
    } else {
      query[f.name] = f.enabledValue
    }
    router.push({
      pathname: router.pathname,
      query
    })
  }

  let search
  if (props.search !== undefined) {
    search = <QuickSearch type={props.search} />
  }

  let filterDropDownElements = []
  if (props.filters !== undefined) {
    props.filters.forEach((f, i) => {
      if (f.title !== undefined && f.enabledValue !== undefined) {
        let currentValue
        if (enabledFilterValues !== undefined) {
          currentValue = enabledFilterValues[f.name]
        }
        let enabled = currentValue === f.enabledValue
        filterDropDownElements.push(
          <li onClick={() => toggleFilter(f, enabled)} key={i}
              className={classNames({ enabled: enabled })}>
            {enabled && <><Check /> </>}
            {f.title}
          </li>
        )
      }
    })
  }

  return (
    <Page {...props}>
      <div className="list-page">
        <div className={classNames("list-page-title", { "no-margin-bottom": props.breadcrumbs })}>
          <h1 className="no-margin-bottom">{props.title}</h1>
          <div className="title-right">
            {props.additionalButtons}
            <div className="search-container">
              {search}
            </div>
            {filterDropDownElements.length > 0 && (
              <DropDown title="Filter" right primary={hasEnabledFilters}>
                <ul className={classNames("filter-list", { "has-enabled-filters": hasEnabledFilters })}>
                  {filterDropDownElements}
                </ul>
              </DropDown>
            )}
          </div>
        </div>
        {props.breadcrumbs && <div className="breadcrumbs"><Breadcrumbs breadcrumbs={props.breadcrumbs} /></div>}
        <props.Context.Provider pageSize={pageSize} shouldAddItem={shouldAddItem} reducers={[reducer]}>
          <List Context={props.Context} ListItem={props.ListItem} subjects={props.subjects}
              path={props.path} pagination={pagination} pageSize={pageSize}
              pageOffset={pageOffset} enabledFilterValues={enabledFilterValues}
              forceUpdate={forceUpdate} />
        </props.Context.Provider>
        {updatesAvailable && (<Notification>
          Updates available. <a href="#" onClick={() =>
            setForceUpdate(forceUpdate + 1)}>Refresh</a>.
        </Notification>)}
      </div>
      <style jsx>{styles}</style>
    </Page>
  )
}

export default ListPage
