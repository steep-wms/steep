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
import WorkflowContext from "../../components/workflows/WorkflowContext"
import { formatDate, formatDurationTitle } from "../../components/lib/date-time-utils"
import workflowToProgress from "../../components/workflows/workflow-to-progress"
import fetcher from "../../components/lib/json-fetcher"
import { disableBodyScroll, enableBodyScroll } from "body-scroll-lock"

function WorkflowDetails({ id }) {
  const workflows = useContext(WorkflowContext.Items)
  const updateWorkflows = useContext(WorkflowContext.UpdateItems)
  const [error, setError] = useState()
  const [cancelModalOpen, setCancelModalOpen] = useState()

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

  function onCancelModalOpen() {
    disableBodyScroll()
  }

  function onCancelModalClose() {
    enableBodyScroll()
  }

  let workflowSource
  let startTime
  let endTime
  let requiredCapabilities
  if (workflows.items !== undefined && workflows.items.length > 0) {
    let w = workflows.items[0]
    id = w.id
    startTime = w.startTime
    endTime = w.endTime
    requiredCapabilities = w.requiredCapabilities
    workflowSource = w.workflow
  }

  const codeBox = useMemo(() => <CodeBox json={workflowSource} />, [workflowSource])

  const breadcrumbs = useMemo(() => [
    <Link href="/workflows/" key="workflows"><a>Workflows</a></Link>,
    id
  ], [id])

  const detailHeaderLeft = useMemo(() => {
    let reqcap
    if (requiredCapabilities === undefined || requiredCapabilities.length === 0) {
      reqcap = <>&ndash;</>
    } else {
      reqcap = requiredCapabilities.map((r, i) => <Label key={i}>{r}</Label>)
    }

    return (
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
          <DefinitionListItem title="Required capabilities">
            {reqcap}
          </DefinitionListItem>
        </DefinitionList>
      </div>
    )
  }, [startTime, endTime, requiredCapabilities])

  let title
  let workflow
  let menu

  if (workflows.items !== undefined && workflows.items.length > 0) {
    let w = workflows.items[0]
    title = w.id

    if (w.status === "ACCEPTED" || w.status === "RUNNING") {
      menu = (
        <ul>
          <li onClick={onCancel}>Cancel</li>
        </ul>
      )
    }

    let progress = workflowToProgress(w)

    workflow = (<>
      <div className="detail-header">
        {detailHeaderLeft}
        <div className="detail-header-right">
          <ListItemProgressBox progress={progress} />
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
    <DetailPage breadcrumbs={breadcrumbs} title={title} menu={menu}>
      {workflow}
      {error}
      <CancelModal isOpen={cancelModalOpen} contentLabel="Cancel modal"
          onAfterOpen={onCancelModalOpen} onAfterClose={onCancelModalClose}
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
