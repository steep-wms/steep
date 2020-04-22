import classNames from "classnames"
import Link from "next/link"
import Page from "../../components/layouts/Page"
import Breadcrumbs from "../../components/Breadcrumbs"
import Alert from "../../components/Alert"
import ListItem from "../../components/ListItem"
import ProcessChainContext from "../../components/processchains/ProcessChainContext"
import "./index.scss"
import { useContext, useEffect, useState } from "react"
import fetcher from "../../components/lib/json-fetcher"

function onProcessChainChanged(processChain) {
  delete processChain.executables

  let href = `/processchains/${processChain.id}`

  let progress = {
    status: processChain.status
  }

  processChain.element = (
    <ListItem key={processChain.id} justAdded={processChain.justAdded}
      linkHref={href} title={processChain.id} startTime={processChain.startTime}
      endTime={processChain.endTime} progress={progress} />
  )
}

function ProcessChainList({ pageSize, pageOffset, submissionId }) {
  const processChains = useContext(ProcessChainContext.State)
  const updateProcessChains = useContext(ProcessChainContext.Dispatch)
  const [error, setError] = useState()

  useEffect(() => {
    let params = new URLSearchParams()
    if (typeof pageOffset !== "undefined") {
      params.append("offset", pageOffset)
    }
    params.append("size", pageSize)
    if (typeof submissionId !== "undefined") {
      params.append("submissionId", submissionId)
    }

    fetcher(`${process.env.baseUrl}/processchains?${params.toString()}`)
      .then(processChains => updateProcessChains({ action: "push", processChains }))
      .catch(err => {
        console.error(err)
        setError(<Alert error>Could not load process chains</Alert>)
      })
  }, [pageOffset, pageSize, submissionId, updateProcessChains])

  return (<>
    {processChains && processChains.map(pc => pc.element)}
    {error}
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
    pageSize = params.get("size") || 10
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
    <Page title="Process chains">
      <div className="process-chain-overview">
        <h1 className={classNames({ "no-margin-bottom": breadcrumbs })}>Process chains</h1>
        {breadcrumbs && <Breadcrumbs breadcrumbs={breadcrumbs} />}
        <ProcessChainContext.Provider pageSize={pageSize} onProcessChainChanged={onProcessChainChanged}>
          <ProcessChainList pageSize={pageSize} pageOffset={pageOffset} submissionId={submissionId} />
        </ProcessChainContext.Provider>
      </div>
    </Page>
  )
}
