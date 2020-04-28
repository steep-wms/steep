import classNames from "classnames"
import Link from "next/link"
import ListPage from "../../components/layouts/ListPage"
import Breadcrumbs from "../../components/Breadcrumbs"
import Alert from "../../components/Alert"
import ListItem from "../../components/ListItem"
import Pagination from "../../components/Pagination"
import ProcessChainContext from "../../components/processchains/ProcessChainContext"
import "./index.scss"
import { useContext, useEffect, useState } from "react"
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

function ProcessChainList({ pageSize, pageOffset, submissionId }) {
  const processChains = useContext(ProcessChainContext.State)
  const updateProcessChains = useContext(ProcessChainContext.Dispatch)
  const [error, setError] = useState()
  const [pageTotal, setPageTotal] = useState(0)

  useEffect(() => {
    let params = new URLSearchParams()
    if (typeof pageOffset !== "undefined") {
      params.append("offset", pageOffset)
    }
    params.append("size", pageSize)
    if (typeof submissionId !== "undefined") {
      params.append("submissionId", submissionId)
    }

    fetcher(`${process.env.baseUrl}/processchains?${params.toString()}`, true)
      .then(r => {
        let processChains = r.body
        updateProcessChains({ action: "set", processChains })
        let pageTotalHeader = r.headers.get("x-page-total")
        if (pageTotalHeader !== null) {
          setPageTotal(+pageTotalHeader)
        }
      })
      .catch(err => {
        console.error(err)
        setError(<Alert error>Could not load process chains</Alert>)
      })
  }, [pageOffset, pageSize, submissionId, updateProcessChains])

  function reset(newOffset) {
    if (newOffset !== pageOffset) {
      updateProcessChains({ action: "set", processChains: undefined })
      setPageTotal(0)
    }
  }

  return (<>
    {processChains && processChains.map(pc => pc.element)}
    {processChains && processChains.length === 0 && <>There are no process chains.</>}
    {error}
    {pageTotal > 0 && (
      <Pagination pageSize={pageSize} pageOffset={pageOffset} pageTotal={pageTotal}
        onChangeOffset={reset} />
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
    if (typeof pageOffset !== "undefined") {
      pageOffset = Math.max(0, parseInt(pageOffset))
    }
    pageSize = params.get("size") || 10
    if (typeof pageSize !== "undefined") {
      pageSize = Math.max(0, parseInt(pageSize))
    }
    submissionId = params.get("submissionId") || undefined
  }

  let [breadcrumbs, setBreadcrumbs] = useState()

  useEffect(() => {
    if (typeof submissionId !== "undefined") {
      setBreadcrumbs([
        <Link href="/workflows" key="workflows"><a>Workflows</a></Link>,
        <Link href="/workflows/[id]" as={`/workflows/${submissionId}`} key={submissionId}>
          <a>{submissionId}</a>
        </Link>,
        "Process chains"
      ])
    }
  }, [submissionId])

  return (
    <ListPage title="Process chains">
      <div className="process-chain-overview">
        <h1 className={classNames({ "no-margin-bottom": breadcrumbs })}>Process chains</h1>
        {breadcrumbs && <Breadcrumbs breadcrumbs={breadcrumbs} />}
        <ProcessChainContext.Provider pageSize={pageSize} onProcessChainChanged={onProcessChainChanged}>
          <ProcessChainList pageSize={pageSize} pageOffset={pageOffset} submissionId={submissionId} />
        </ProcessChainContext.Provider>
      </div>
    </ListPage>
  )
}
