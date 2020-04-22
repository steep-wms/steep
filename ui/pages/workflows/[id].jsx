import DetailPage from "../../components/layouts/DetailPage"
import Link from "next/link"
import { useRouter } from "next/router"
import { useContext, useEffect, useState } from "react"
import Alert from "../../components/Alert"
import CodeBox from "../../components/CodeBox"
import DefinitionList from "../../components/DefinitionList"
import DefinitionListItem from "../../components/DefinitionListItem"
import ListItemProgressBox from "../../components/ListItemProgressBox"
import LiveDuration from "../../components/LiveDuration"
import WorkflowContext from "../../components/workflows/WorkflowContext"
import { formatDate, formatDurationTitle } from "../../components/lib/date-time-utils"
import workflowToProgress from "../../components/workflows/workflow-to-progress"
import fetcher from "../../components/lib/json-fetcher"

function Workflow({ id }) {
  const workflows = useContext(WorkflowContext.State)
  const updateWorkflows = useContext(WorkflowContext.Dispatch)
  const [error, setError] = useState()

  useEffect(() => {
    if (id) {
      fetcher(`${process.env.baseUrl}/workflows/${id}`)
        .then(workflow => updateWorkflows({ action: "push", workflows: [workflow] }))
        .catch(err => {
          console.error(err)
          setError(<Alert error>Could not load workflow</Alert>)
        })
    }
  }, [id, updateWorkflows])

  let breadcrumbs
  let title
  let workflow

  if (typeof workflows !== "undefined" && workflows.length > 0) {
    let w = workflows[0]
    title = w.id
    breadcrumbs = [
      <Link href="/workflows" key="workflows"><a>Workflows</a></Link>,
      w.id
    ]

    let progress = workflowToProgress(w)

    workflow = (<>
      <div className="detail-header">
        <div className="detail-header-left">
          <DefinitionList>
            <DefinitionListItem title="Start time">
              {w.startTime ? formatDate(w.startTime) : <>&ndash;</>}
            </DefinitionListItem>
            <DefinitionListItem title="End time">
              {w.endTime ? formatDate(w.endTime) : <>&ndash;</>}
            </DefinitionListItem>
            <DefinitionListItem title="Time elapsed">
              {
                w.startTime && w.endTime ? formatDurationTitle(w.startTime, w.endTime) : (
                  w.startTime ? <LiveDuration startTime={w.startTime} /> : <>&ndash;</>
                )
              }
            </DefinitionListItem>
          </DefinitionList>
        </div>
        <div className="detail-header-right">
          <ListItemProgressBox progress={progress} />
        </div>
      </div>
      {w.errorMessage && (<>
        <h2>Error message</h2>
        <Alert error>{w.errorMessage}</Alert>
      </>)}
      <h2>Source</h2>
      <CodeBox json={w.workflow} />
    </>)
  }

  return (
    <DetailPage breadcrumbs={breadcrumbs} title={title}>
      {workflow}
      {error}
    </DetailPage>
  )
}

export default () => {
  const router = useRouter()
  const { id } = router.query

  return (
    <WorkflowContext.Provider pageSize={1} allowAdd={false}>
      <Workflow id={id} />
    </WorkflowContext.Provider>
  )
}
