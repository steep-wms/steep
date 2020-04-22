import Page from "../../components/layouts/Page"
import Alert from "../../components/Alert"
import ListItem from "../../components/ListItem"
import WorkflowContext from "../../components/workflows/WorkflowContext"
import { useContext, useEffect, useState } from "react"
import workflowToProgress from "../../components/lib/workflow-to-progress"
import fetcher from "../../components/lib/json-fetcher"

function onWorkflowChanged(workflow) {
  delete workflow.workflow

  let href = `/workflows/${workflow.id}`

  let status = workflow.status
  if (status === "RUNNING" && workflow.cancelledProcessChains > 0) {
    status = "CANCELLING"
  }

  let progress = workflowToProgress(workflow)

  workflow.element = (
    <ListItem key={workflow.id} justAdded={workflow.justAdded}
      linkHref={href} title={workflow.id} startTime={workflow.startTime}
      endTime={workflow.endTime} progress={progress} />
  )
}

function WorkflowList({ pageSize, pageOffset }) {
  const workflows = useContext(WorkflowContext.State)
  const updateWorkflows = useContext(WorkflowContext.Dispatch)
  const [error, setError] = useState()

  useEffect(() => {
    let params = new URLSearchParams()
    if (typeof pageOffset !== "undefined") {
      params.append("offset", pageOffset)
    }
    params.append("size", pageSize)

    fetcher(`${process.env.baseUrl}/workflows?${params.toString()}`)
      .then(workflows => updateWorkflows({ action: "push", workflows }))
      .catch(err => {
        console.error(err)
        setError(<Alert error>Could not load workflows</Alert>)
      })
  }, [pageOffset, pageSize, updateWorkflows])

  return (<>
    {workflows && workflows.map(w => w.element)}
    {error}
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
    pageSize = params.get("size") || 10
  }

  return (
    <Page title="Workflows">
      <h1>Workflows</h1>
      <WorkflowContext.Provider pageSize={pageSize} onWorkflowChanged={onWorkflowChanged}>
        <WorkflowList pageSize={pageSize} pageOffset={pageOffset} />
      </WorkflowContext.Provider>
    </Page>
  )
}
