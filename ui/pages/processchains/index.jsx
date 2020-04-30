import classNames from "classnames"
import Link from "next/link"
import ListPage from "../../components/layouts/ListPage"
import Alert from "../../components/Alert"
import Breadcrumbs from "../../components/Breadcrumbs"
import DropDown from "../../components/DropDown"
import ListItem from "../../components/ListItem"
import Notification from "../../components/Notification"
import Pagination from "../../components/Pagination"
import ProcessChainContext from "../../components/processchains/ProcessChainContext"
import "./index.scss"
import { useCallback, useContext, useEffect, useState } from "react"
import { useRouter } from "next/router"
import fetcher from "../../components/lib/json-fetcher"
import { Check } from "react-feather"

function onProcessChainChanged(processChain) {
  delete processChain.executables

  let href = "/processchains/[id]"
  let as = `/processchains/${processChain.id}`

  let progress = {
    status: processChain.status
  }

  processChain.element = (
    <ListItem key={processChain.id} justAdded={processChain.justAdded}
      linkHref={href} linkAs={as} title={processChain.id} startTime={processChain.startTime}
      endTime={processChain.endTime} progress={progress} />
  )
}

function ProcessChainList({ pageSize, pageOffset, submissionId, status, forceUpdate }) {
  const processChains = useContext(ProcessChainContext.ProcessChains)
  const updateProcessChains = useContext(ProcessChainContext.UpdateProcessChains)
  const addedProcessChains = useContext(ProcessChainContext.AddedProcessChains)
  const updateAddedProcessChains = useContext(ProcessChainContext.UpdateAddedProcessChains)
  const [error, setError] = useState()
  const [pageTotal, setPageTotal] = useState(0)

  const forceReset = useCallback(() => {
    updateProcessChains({ action: "set", processChains: undefined })
    setPageTotal(0)
    updateAddedProcessChains({ action: "set", n: 0 })
  }, [updateProcessChains, updateAddedProcessChains])

  useEffect(() => {
    let params = new URLSearchParams()
    if (pageOffset !== undefined) {
      params.append("offset", pageOffset)
    }
    params.append("size", pageSize)
    if (submissionId !== undefined) {
      params.append("submissionId", submissionId)
    }
    if (status !== undefined) {
      params.append("status", status)
    }

    forceReset()

    fetcher(`${process.env.baseUrl}/processchains?${params.toString()}`, true)
      .then(r => {
        let processChains = r.body
        updateProcessChains({ action: "set", processChains })
        let pageTotalHeader = r.headers.get("x-page-total")
        if (pageTotalHeader !== null) {
          setPageTotal(+pageTotalHeader)
          updateAddedProcessChains({ action: "set", n: 0 })
        }
      })
      .catch(err => {
        console.error(err)
        setError(<Alert error>Could not load process chains</Alert>)
      })
  }, [pageOffset, pageSize, submissionId, status, updateProcessChains,
      updateAddedProcessChains, forceUpdate, forceReset])

  function reset(newOffset) {
    if (newOffset !== pageOffset) {
      forceReset()
    }
  }

  return (<>
    {processChains && processChains.map(pc => pc.element)}
    {processChains && processChains.length === 0 && <>There are no process chains.</>}
    {error}
    {pageTotal + addedProcessChains > 0 && (
      <Pagination pageSize={pageSize} pageOffset={pageOffset}
        pageTotal={pageTotal + addedProcessChains} onChangeOffset={reset} />
    )}
  </>)
}

export default () => {
  // parse query params but do not use "next/router" because router.query
  // is empty on initial render
  let pageOffset
  let pageSize
  let submissionId
  let status
  if (typeof window !== "undefined") {
    let params = new URLSearchParams(window.location.search)
    pageOffset = params.get("offset") || undefined
    if (pageOffset !== undefined) {
      pageOffset = Math.max(0, parseInt(pageOffset))
    }
    pageSize = params.get("size") || 10
    if (pageSize !== undefined) {
      pageSize = Math.max(0, parseInt(pageSize))
    }
    submissionId = params.get("submissionId") || undefined
    status = params.get("status") || undefined
  }

  const router = useRouter()
  const [breadcrumbs, setBreadcrumbs] = useState()
  const [updatesAvailable, setUpdatesAvailable] = useState(false)
  const [forceUpdate, setForceUpdate] = useState(0)
  const [filtersActive, setFiltersActive] = useState(false)
  const [filterFailedOnly, setFilterFailedOnly] = useState(false)

  useEffect(() => {
    if (submissionId !== undefined) {
      setBreadcrumbs([
        <Link href="/workflows" key="workflows"><a>Workflows</a></Link>,
        <Link href="/workflows/[id]" as={`/workflows/${submissionId}`} key={submissionId}>
          <a>{submissionId}</a>
        </Link>,
        "Process chains"
      ])
    } else {
      setBreadcrumbs(undefined)
    }
  }, [submissionId])

  useEffect(() => {
    setFiltersActive(status !== undefined)
    setFilterFailedOnly(status === "ERROR")
  }, [status])

  useEffect(() => {
    setUpdatesAvailable(false)
  }, [pageOffset, pageSize, submissionId, status, forceUpdate])

  const addFilter = useCallback((processChain) => {
    let result = true
    if (submissionId !== undefined) {
      result = result && processChain.submissionId === submissionId
    }
    if (status !== undefined) {
      result = result && processChain.status === status
    }
    if (result && pageOffset > 0) {
      setUpdatesAvailable(true)
      return false
    }
    return result
  }, [submissionId, status, pageOffset])

  const onStatusChanged = useCallback(({ submissionId: sid, status: st }) => {
    if (status === st) {
      setUpdatesAvailable(submissionId === undefined || submissionId === sid)
    }
  }, [submissionId, status])

  function toggleFilterFailedOnly() {
    let query = { ...router.query }
    delete query.offset
    if (filterFailedOnly) {
      delete query.status
    } else {
      query.status = "ERROR"
    }
    router.push({
      pathname: router.pathname,
      query
    })
  }

  return (
    <ListPage title="Process chains">
      <div className="process-chain-overview">
        <div className={classNames("process-chain-title", { "no-margin-bottom": breadcrumbs })}>
          <h1 className="no-margin-bottom">Process chains</h1>
          <DropDown title="Filter" right primary={filtersActive}>
            <ul>
              <li onClick={toggleFilterFailedOnly}>
                {filterFailedOnly && <><Check className="feather" /> </>}
                Failed process chains only
              </li>
            </ul>
          </DropDown>
        </div>
        {breadcrumbs && <Breadcrumbs breadcrumbs={breadcrumbs} />}
        <ProcessChainContext.Provider pageSize={pageSize} addFilter={addFilter}
            onStatusChanged={onStatusChanged} onProcessChainChanged={onProcessChainChanged}>
          <ProcessChainList pageSize={pageSize} pageOffset={pageOffset}
              submissionId={submissionId} status={status} forceUpdate={forceUpdate} />
        </ProcessChainContext.Provider>
        {updatesAvailable && (<Notification>
          New process chains available. <a href="#" onClick={() =>
            setForceUpdate(forceUpdate + 1)}>Refresh</a>.
        </Notification>)}
      </div>
    </ListPage>
  )
}
