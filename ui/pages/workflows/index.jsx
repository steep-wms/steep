import ListPage from "../../components/layouts/ListPage"
import ListItem from "../../components/ListItem"
import Tooltip from "../../components/Tooltip"
import WorkflowContext from "../../components/workflows/WorkflowContext"
import { useMemo } from "react"
import Link from "next/link"
import { PlusSquare } from "lucide-react"
import workflowToProgress from "../../components/workflows/workflow-to-progress"
import styles from "./index.scss"

const FILTERS = [{
  name: "status",
  title: "Failed workflows only",
  enabledValue: "ERROR"
}, {
  name: "status",
  title: "Partially succeeded workflows only",
  enabledValue: "PARTIAL_SUCCESS"
}, {
  name: "status",
  title: "Running workflows only",
  enabledValue: "RUNNING"
}]

function WorkflowListItem({ item: workflow }) {
  return useMemo(() => {
    let href = "/workflows/[id]"
    let as = `/workflows/${workflow.id}`
    let title = workflow.name || workflow.id

    let progress = workflowToProgress(workflow)

    return <ListItem key={workflow.id} justAdded={workflow.justAdded}
        deleted={workflow.deleted} linkHref={href} linkAs={as} title={title}
        startTime={workflow.startTime} endTime={workflow.endTime}
        progress={progress} labels={workflow.requiredCapabilities} />
  }, [workflow])
}

const Workflows = () => {
  let additionalButtons = <>
    <Tooltip title="New workflow &hellip;">
      <Link href="/new/workflow"><a className="plus-button"><PlusSquare /></a></Link>
    </Tooltip>
    <style jsx>{styles}</style>
  </>

  return (
    <ListPage title="Workflows" Context={WorkflowContext}
        ListItem={WorkflowListItem} subjects="workflows" path="workflows"
        filters={FILTERS} search="workflow"
        additionalButtons={additionalButtons} />
  )
}

export default Workflows
