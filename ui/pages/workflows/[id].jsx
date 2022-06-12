import DetailPage from "../../components/layouts/DetailPage"
import Link from "next/link"
import { useRouter } from "next/router"
import { useContext, useEffect, useMemo, useState } from "react"
import Alert from "../../components/Alert"
import CancelModal from "../../components/CancelModal"
import CodeBox from "../../components/CodeBox"
import DefinitionList from "../../components/DefinitionList"
import DefinitionListItem from "../../components/DefinitionListItem"
import Label from "../../components/Label"
import ListItemProgressBox from "../../components/ListItemProgressBox"
import LiveDuration from "../../components/LiveDuration"
import Priority from "../../components/Priority"
import WorkflowContext from "../../components/workflows/WorkflowContext"
import { formatDate, formatDurationTitle } from "../../components/lib/date-time-utils"
import submissionToSource from "../../components/lib/submission-source"
import workflowToProgress from "../../components/workflows/workflow-to-progress"
import fetcher from "../../components/lib/json-fetcher"
import styles from "./[id].scss"

function WorkflowDetails({ id }) {
  const workflows = useContext(WorkflowContext.Items)
  const updateWorkflows = useContext(WorkflowContext.UpdateItems)
  const [error, setError] = useState()
  const [cancelModalOpen, setCancelModalOpen] = useState()
  const router = useRouter()

  useEffect(() => {
    if (id) {
      fetcher(`${process.env.baseUrl}/workflows/${id}`)
        .then(workflow => updateWorkflows({ action: "set", items: [workflow] }))
        .catch(err => {
          console.error(err)
          setError(<Alert error>Could not load workflow</Alert>)
        })
    }
  }, [id, updateWorkflows])

  function onCancel() {
    setCancelModalOpen(true)
  }

  function onDoCancel() {
    setCancelModalOpen(false)
    fetcher(`${process.env.baseUrl}/workflows/${id}`, false, {
      method: "PUT",
      body: JSON.stringify({
        status: "CANCELLED"
      })
    }).catch(error => {
      console.error(error)
    })
  }

  function onResubmit() {
    router.push({
      pathname: "/new/workflow",
      query: {
        from: id
      }
    })
  }

  let workflowSource
  let startTime
  let endTime
  let requiredCapabilities
  let priority
  let deleted
  let status
  if (workflows.items !== undefined && workflows.items.length > 0) {
    let w = workflows.items[0]
    id = w.id
    startTime = w.startTime
    endTime = w.endTime
    requiredCapabilities = w.requiredCapabilities
    priority = w.priority
    workflowSource = submissionToSource(w)
    deleted = !!w.deleted
    status = w.status
  }

  const codeBox = useMemo(() => (workflowSource &&
      <CodeBox json={workflowSource.json} yaml={workflowSource.yaml} />),
      [workflowSource])

  const breadcrumbs = useMemo(() => [
    <Link href="/workflows/" key="workflows"><a>Workflows</a></Link>,
    id
  ], [id])

  const detailHeaderLeft = useMemo(() => {
    function onDoChangePriority(priority) {
      fetcher(`${process.env.baseUrl}/workflows/${id}`, false, {
        method: "PUT",
        body: JSON.stringify({
          priority
        })
      }).catch(error => {
        console.error(error)
      })
    }

    let reqcap
    if (requiredCapabilities === undefined || requiredCapabilities.length === 0) {
      reqcap = <>&ndash;</>
    } else {
      reqcap = requiredCapabilities.flatMap((r, i) => [<Label key={i}>{r}</Label>, <wbr key={`wbr${i}`}/>])
    }

    return (<div className="left-two-columns">
      <div className="detail-header-left">
        <DefinitionList>
          <DefinitionListItem title="Start time">
            {startTime ? formatDate(startTime) : <>&ndash;</>}
          </DefinitionListItem>
          <DefinitionListItem title="End time">
            {endTime ? formatDate(endTime) : <>&ndash;</>}
          </DefinitionListItem>
          <DefinitionListItem title="Time elapsed">
            {
              startTime && endTime ? formatDurationTitle(startTime, endTime) : (
                startTime ? <LiveDuration startTime={startTime} /> : <>&ndash;</>
              )
            }
          </DefinitionListItem>
        </DefinitionList>
      </div>
      <div className="detail-header-middle">
        <DefinitionList>
          <DefinitionListItem title="Priority">
            <Priority value={priority} onChange={v => onDoChangePriority(v)}
              subjectShort="workflow" subjectLong="workflow and all its process chains"
              editable={status === "ACCEPTED" || status === "RUNNING"} />
          </DefinitionListItem>
          <DefinitionListItem title="Required capabilities">
            {reqcap}
          </DefinitionListItem>
        </DefinitionList>
      </div>
      <style jsx>{styles}</style>
    </div>)
  }, [id, startTime, endTime, requiredCapabilities, priority, status])

  let title
  let workflow
  let menu

  if (workflows.items !== undefined && workflows.items.length > 0) {
    let w = workflows.items[0]
    title = w.name || w.id

    if (status === "ACCEPTED" || status === "RUNNING") {
      menu = (
        <ul>
          <li onClick={onCancel}>Cancel</li>
        </ul>
      )
    } else {
      menu = (
        <ul>
          <li onClick={onResubmit}>Resubmit</li>
        </ul>
      )
    }

    let progress = workflowToProgress(w)

    workflow = (<>
      <div className="detail-header">
        {detailHeaderLeft}
        <div className="detail-header-right">
          <ListItemProgressBox progress={progress} deleted={deleted} />
        </div>
      </div>
      {w.errorMessage && (<>
        <h2>Error message</h2>
        <Alert error>{w.errorMessage}</Alert>
      </>)}
      <h2>Source</h2>
      {codeBox}
    </>)
  }

  return (
    <DetailPage breadcrumbs={breadcrumbs} title={title} menu={menu} deleted={deleted}>
      {workflow}
      {error}
      <CancelModal isOpen={cancelModalOpen} contentLabel="Cancel modal"
          onRequestClose={() => setCancelModalOpen(false)} title="Cancel workflow"
          onConfirm={onDoCancel} onDeny={() => setCancelModalOpen(false)}>
        <p>Are you sure you want to cancel this workflow?</p>
      </CancelModal>
    </DetailPage>
  )
}

const Workflow = () => {
  const router = useRouter()
  const { id } = router.query

  return (
    <WorkflowContext.Provider pageSize={1} allowAdd={false}>
      <WorkflowDetails id={id} />
    </WorkflowContext.Provider>
  )
}

export default Workflow
