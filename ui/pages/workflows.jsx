import EventBusContext from "../components/lib/EventBusContext"
import Page from "../components/layouts/Page"
import ListItem from "../components/ListItem"
import { useContext, useEffect, useReducer } from "react"
import useSWR from "swr"
import fetcher from "../components/lib/json-fetcher"
import TimeAgo from "react-timeago"
import { formatDistanceToNow } from "date-fns"
import moment from "moment"

const ADDRESS_SUBMISSION_ADDED = "steep.submissionRegistry.submissionAdded"

function addWorkflowReducer(state, { type = "unshift", workflow }) {
  if (state.findIndex(w => w.id === workflow.id) >= 0) {
    return state
  }
  switch (type) {
    case "push":
      return [...state, workflow]
    case "unshift":
      return [workflow, ...state]
    default:
      return state
  }
}

export default (props) => {
  const [workflows, addWorkflow] = useReducer(addWorkflowReducer, []);
  const eventBus = useContext(EventBusContext)
  const { data: fetchedWorkflows, error: fetchedWorkflowsError } =
      useSWR(process.env.baseUrl + "/workflows", fetcher)

  function initWorkflow(w) {
    delete w.workflow
    w.runningProcessChains = w.runningProcessChains || 0
    w.succeededProcessChains = w.succeededProcessChains || 0
    w.cancelledProcessChains = w.cancelledProcessChains || 0
    w.failedProcessChains = w.failedProcessChains || 0
    w.totalProcessChains = w.totalProcessChains || 0
    w.startTime = w.startTime || null
    w.endTime = w.endTime || null
  }

  function onSubmissionAdded(error, message) {
    let workflow = message.body
    initWorkflow(workflow)
    workflow.justAdded = true
    addWorkflow({ workflow })
  }

  function formatterToNow(value, unit, suffix, epochSeconds) {
    return formatDistanceToNow(epochSeconds, { addSuffix: true, includeSeconds: true })
  }

  function workflowDuration(w) {
    let diff = moment(w.endTime).diff(moment(w.startTime))
    let duration = Math.ceil(moment.duration(diff).asSeconds())
    let seconds = Math.floor(duration % 60)
    let minutes = Math.floor(duration / 60 % 60)
    let hours = Math.floor(duration / 60 / 60)
    let result = ""
    if (hours > 0) {
      result += hours + "h "
    }
    if (result !== "" || minutes > 0) {
      result += minutes + "m "
    }
    result += seconds + "s"
    return result
  }

  useEffect(() => {
    if (eventBus) {
      eventBus.registerHandler(ADDRESS_SUBMISSION_ADDED, onSubmissionAdded)
    }

    return () => {
      if (eventBus) {
        eventBus.unregisterHandler(ADDRESS_SUBMISSION_ADDED, onSubmissionAdded)
      }
    }
  }, [eventBus])

  let workflowLoading
  let workflowError
  let workflowElements = []

  if (typeof fetchedWorkflowsError !== "undefined") {
    workflowError = "Could not load workflows"
    console.error(fetchedWorkflowsError)
  } else if (typeof fetchedWorkflows === "undefined") {
    workflowLoading = "Loading ..."
  } else {
    for (let workflow of fetchedWorkflows) {
      if (workflows.findIndex(w => w.id === workflow.id) < 0) {
        initWorkflow(workflow)
        addWorkflow({ type: "push", workflow })
      }
    }

    for (let workflow of workflows) {
      let diff = moment(workflow.endTime).diff(moment(workflow.startTime))
      let duration = moment.duration(diff).humanize()
      let timeAgoTitle = moment(workflow.endTime).format("dddd, D MMMM YYYY, h:mm:ss a")
      let durationTitle = workflowDuration(workflow)
      let subtitle = (<>
        Finished <TimeAgo date={workflow.endTime} formatter={formatterToNow} title={timeAgoTitle} /> and
        took <span title={durationTitle}>{duration}</span>
      </>)
      workflowElements.push(
        <ListItem key={workflow.id} justAdded={workflow.justAdded}
          title={workflow.id} subtitle={subtitle} />
      )
    }
  }

  return (
    <Page>
      <h1>Workflows</h1>
      {workflowElements}
      {workflowLoading}
      {workflowError}
    </Page>
  )
}
