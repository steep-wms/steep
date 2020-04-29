import classNames from "classnames"
import Link from "next/link"
import ListPage from "../../components/layouts/ListPage"
import Breadcrumbs from "../../components/Breadcrumbs"
import Alert from "../../components/Alert"
import ListItem from "../../components/ListItem"
import Notification from "../../components/Notification"
import Pagination from "../../components/Pagination"
import ProcessChainContext from "../../components/processchains/ProcessChainContext"
import "./index.scss"
import { useCallback, useContext, useEffect, useState } from "react"
import fetcher from "../../components/lib/json-fetcher"

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

function ProcessChainList({ pageSize, pageOffset, submissionId, forceUpdate }) {
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
  }, [pageOffset, pageSize, submissionId, updateProcessChains,
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
  }

  const [breadcrumbs, setBreadcrumbs] = useState()
  const [updatesAvailable, setUpdatesAvailable] = useState(false)
  const [forceUpdate, setForceUpdate] = useState(0)

  useEffect(() => {
    if (submissionId !== undefined) {
      setBreadcrumbs([
        <Link href="/workflows" key="workflows"><a>Workflows</a></Link>,
        <Link href="/workflows/[id]" as={`/workflows/${submissionId}`} key={submissionId}>
          <a>{submissionId}</a>
        </Link>,
        "Process chains"
      ])
    }
  }, [submissionId])

  useEffect(() => {
    setUpdatesAvailable(false)
  }, [pageOffset, pageSize, submissionId, forceUpdate])

  const addFilter = useCallback((processChain) => {
    if (submissionId !== undefined) {
      return processChain.submissionId === submissionId
    }
    if (pageOffset > 0) {
      setUpdatesAvailable(true)
      return false
    }
    return true
  }, [submissionId, pageOffset])

  return (
    <ListPage title="Process chains">
      <div className="process-chain-overview">
        <h1 className={classNames({ "no-margin-bottom": breadcrumbs })}>Process chains</h1>
        {breadcrumbs && <Breadcrumbs breadcrumbs={breadcrumbs} />}
        <ProcessChainContext.Provider pageSize={pageSize} addFilter={addFilter}
            onProcessChainChanged={onProcessChainChanged}>
          <ProcessChainList pageSize={pageSize} pageOffset={pageOffset}
              submissionId={submissionId} forceUpdate={forceUpdate} />
        </ProcessChainContext.Provider>
        {updatesAvailable && (<Notification>
          New process chains available. <a href="#" onClick={() =>
            setForceUpdate(forceUpdate + 1)}>Refresh</a>.
        </Notification>)}
      </div>
    </ListPage>
  )
}
