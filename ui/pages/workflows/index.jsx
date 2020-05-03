import ListPage from "../../components/layouts/ListPage"
import ListItem from "../../components/ListItem"
import WorkflowContext from "../../components/workflows/WorkflowContext"
import { useMemo } from "react"
import workflowToProgress from "../../components/workflows/workflow-to-progress"

function WorkflowListItem({ item: workflow }) {
  return useMemo(() => {
    let href = "/workflows/[id]"
    let as = `/workflows/${workflow.id}`

    let progress = workflowToProgress(workflow)

    return <ListItem key={workflow.id} justAdded={workflow.justAdded}
        linkHref={href} linkAs={as} title={workflow.id}
        startTime={workflow.startTime} endTime={workflow.endTime}
        progress={progress} labels={workflow.requiredCapabilities} />
  }, [workflow])
}

export default () => (
  <ListPage title="Workflows" Context={WorkflowContext}
      ListItem={WorkflowListItem} subjects="workflows" path="workflows" />
)
