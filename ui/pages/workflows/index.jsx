import ListPage from "../../components/layouts/ListPage"
import Alert from "../../components/Alert"
import ListItem from "../../components/ListItem"
import Notification from "../../components/Notification"
import Pagination from "../../components/Pagination"
import WorkflowContext from "../../components/workflows/WorkflowContext"
import { useCallback, useContext, useEffect, useState } from "react"
import workflowToProgress from "../../components/workflows/workflow-to-progress"
import fetcher from "../../components/lib/json-fetcher"

function onWorkflowChanged(workflow) {
  delete workflow.workflow

  let href = "/workflows/[id]"
  let as = `/workflows/${workflow.id}`

  let progress = workflowToProgress(workflow)

  workflow.element = (
    <ListItem key={workflow.id} justAdded={workflow.justAdded}
      linkHref={href} linkAs={as} title={workflow.id} startTime={workflow.startTime}
      endTime={workflow.endTime} progress={progress} />
  )
}

function WorkflowList({ pageSize, pageOffset, forceUpdate }) {
  const workflows = useContext(WorkflowContext.Workflows)
  const updateWorkflows = useContext(WorkflowContext.UpdateWorkflows)
  const addedWorkflows = useContext(WorkflowContext.AddedWorkflows)
  const updateAddedWorkflows = useContext(WorkflowContext.UpdateAddedWorkflows)
  const [error, setError] = useState()
  const [pageTotal, setPageTotal] = useState(0)

  const forceReset = useCallback(() => {
    updateWorkflows({ action: "set", workflows: undefined })
    setPageTotal(0)
    updateAddedWorkflows({ action: "set", n: 0 })
  }, [updateWorkflows, updateAddedWorkflows])

  useEffect(() => {
    let params = new URLSearchParams()
    if (pageOffset !== undefined) {
      params.append("offset", pageOffset)
    }
    params.append("size", pageSize)

    forceReset()

    fetcher(`${process.env.baseUrl}/workflows?${params.toString()}`, true)
      .then(r => {
        let workflows = r.body
        updateWorkflows({ action: "set", workflows })
        let pageTotalHeader = r.headers.get("x-page-total")
        if (pageTotalHeader !== null) {
          setPageTotal(+pageTotalHeader)
          updateAddedWorkflows({ action: "set", n: 0 })
        }
      })
      .catch(err => {
        console.error(err)
        setError(<Alert error>Could not load workflows</Alert>)
      })
  }, [pageOffset, pageSize, updateWorkflows, updateAddedWorkflows,
      forceUpdate, forceReset])

  function reset(newOffset) {
    if (newOffset !== pageOffset) {
      forceReset()
    }
  }

  return (<>
    {workflows && workflows.map(w => w.element)}
    {workflows && workflows.length === 0 && <>There are no workflows.</>}
    {error}
    {pageTotal + addedWorkflows > 0 && (
      <Pagination pageSize={pageSize} pageOffset={pageOffset}
        pageTotal={pageTotal + addedWorkflows} onChangeOffset={reset} />
    )}
  </>)
}

export default () => {
  // parse query params but do not use "next/router" because router.query
  // is empty on initial render
  let pageOffset
  let pageSize
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
  }

  const [updatesAvailable, setUpdatesAvailable] = useState(false)
  const [forceUpdate, setForceUpdate] = useState(0)

  useEffect(() => {
    setUpdatesAvailable(false)
  }, [pageOffset, pageSize, forceUpdate])

  const addFilter = useCallback(() => {
    if (pageOffset > 0) {
      setUpdatesAvailable(true)
      return false
    }
    return true
  }, [pageOffset])

  return (
    <ListPage title="Workflows">
      <h1>Workflows</h1>
      <WorkflowContext.Provider pageSize={pageSize} addFilter={addFilter}
          onWorkflowChanged={onWorkflowChanged}>
        <WorkflowList pageSize={pageSize} pageOffset={pageOffset}
            forceUpdate={forceUpdate} />
      </WorkflowContext.Provider>
      {updatesAvailable && (<Notification>
        New workflows available. <a href="#" onClick={() =>
          setForceUpdate(forceUpdate + 1)}>Refresh</a>.
      </Notification>)}
    </ListPage>
  )
}
