import EventBusContext from "../components/lib/EventBusContext"
import EventBus from "vertx3-eventbus-client"
import Page from "../components/layouts/Page"
import Alert from "../components/Alert"
import ListItem from "../components/ListItem"
import { useContext, useEffect, useReducer } from "react"
import useSWR from "swr"
import fetcher from "../components/lib/json-fetcher"
import TimeAgo from "react-timeago"
import { formatDistanceToNow } from "date-fns"

import dayjs from "dayjs"
import Duration from "dayjs/plugin/duration"
import RelativeTime from "dayjs/plugin/relativeTime"
dayjs.extend(Duration)
dayjs.extend(RelativeTime)

const ADDRESS_SUBMISSION_ADDED = "steep.submissionRegistry.submissionAdded"
const ADDRESS_SUBMISSION_STATUS_CHANGED = "steep.submissionRegistry.submissionStatusChanged"

function updateWorkflowsReducer(state, { action = "unshift", workflow }) {
  let i = state.findIndex(w => w.id === workflow.id)
  if (i >= 0 && action !== "update") {
    return state
  }
  switch (action) {
    case "update": {
      let newWorkflow = { ...state[i], ...workflow }
      return [...state.slice(0, i), newWorkflow, ...state.slice(i + 1)]
    }

    case "push":
      return [...state, workflow]

    case "unshift":
      return [workflow, ...state]

    default:
      return state
  }
}

export default () => {
  const [workflows, updateWorkflows] = useReducer(updateWorkflowsReducer, [])
  const eventBus = useContext(EventBusContext)
  const { data: fetchedWorkflows, error: fetchedWorkflowsError } =
      useSWR(process.env.baseUrl + "/workflows", fetcher)

  function initWorkflow(w) {
    delete w.workflow
    w.runningProcessChains = w.runningProcessChains || 0
    w.succeededProcessChains = w.succeededProcessChains || 0
    w.cancelledProcessChains = w.cancelledProcessChains || 0
    w.failedProcessChains = w.failedProcessChains || 0
    w.totalProcessChains = w.totalProcessChains || 0
    w.startTime = w.startTime || null
    w.endTime = w.endTime || null
  }

  function formatterToNow(value, unit, suffix, epochSeconds) {
    return formatDistanceToNow(epochSeconds, { addSuffix: true, includeSeconds: true })
  }

  function workflowDuration(w) {
    let diff = dayjs(w.endTime).diff(dayjs(w.startTime))
    let duration = Math.ceil(dayjs.duration(diff).asSeconds())
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
    function onSubmissionAdded(error, message) {
      let workflow = message.body
      initWorkflow(workflow)
      workflow.justAdded = true
      updateWorkflows({ action: "unshift", workflow })
    }

    function onSubmissionStatusChanged(error, message) {
      updateWorkflows({
        action: "update", workflow: {
          id: message.body.submissionId,
          status: message.body.status
        }
      })
    }

    if (eventBus) {
      eventBus.registerHandler(ADDRESS_SUBMISSION_ADDED, onSubmissionAdded)
      eventBus.registerHandler(ADDRESS_SUBMISSION_STATUS_CHANGED, onSubmissionStatusChanged)
    }

    return () => {
      if (eventBus && eventBus.state === EventBus.OPEN) {
        eventBus.unregisterHandler(ADDRESS_SUBMISSION_STATUS_CHANGED, onSubmissionStatusChanged)
        eventBus.unregisterHandler(ADDRESS_SUBMISSION_ADDED, onSubmissionAdded)
      }
    }
  }, [eventBus])

  let workflowError
  let workflowElements = []

  if (typeof fetchedWorkflowsError !== "undefined") {
    workflowError = <Alert error>Could not load workflows</Alert>
    console.error(fetchedWorkflowsError)
  } else if (typeof fetchedWorkflows !== "undefined") {
    for (let workflow of fetchedWorkflows) {
      if (workflows.findIndex(w => w.id === workflow.id) < 0) {
        initWorkflow(workflow)
        updateWorkflows({ action: "push", workflow })
      }
    }

    for (let workflow of workflows) {
      let diff = dayjs(workflow.endTime).diff(dayjs(workflow.startTime))
      let duration = dayjs.duration(diff).humanize()
      let timeAgoTitle = dayjs(workflow.endTime).format("dddd, D MMMM YYYY, h:mm:ss a")
      let durationTitle = workflowDuration(workflow)
      let subtitle = (<>
        Finished <TimeAgo date={workflow.endTime} formatter={formatterToNow} title={timeAgoTitle} /> and
        took <span title={durationTitle}>{duration}</span>
      </>)
      let href = `/workflows/${workflow.id}`
      let progress = {
        status: workflow.status,
        subtitle: <span>2 completed</span>
      }
      workflowElements.push(
        <ListItem key={workflow.id} justAdded={workflow.justAdded}
          linkHref={href} title={workflow.id} subtitle={subtitle} progress={progress} />
      )
    }
  }

  return (
    <Page>
      <h1>Workflows</h1>
      {workflowElements}
      {workflowError}
    </Page>
  )
}
