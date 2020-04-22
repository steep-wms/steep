import EventBusContext from "../../components/lib/EventBusContext"
import EventBus from "vertx3-eventbus-client"
import DetailPage from "../../components/layouts/DetailPage"
import Link from "next/link"
import { useRouter } from "next/router"
import { useContext, useEffect, useReducer, useState } from "react"
import Alert from "../../components/Alert"
import CodeBox from "../../components/CodeBox"
import DefinitionList from "../../components/DefinitionList"
import DefinitionListItem from "../../components/DefinitionListItem"
import ListItemProgressBox from "../../components/ListItemProgressBox"
import LiveDuration from "../../components/LiveDuration"
import { formatDate, formatDurationTitle } from "../../components/lib/date-time-utils"
import workflowToProgress from "../../components/lib/workflow-to-progress"
import fetcher from "../../components/lib/json-fetcher"

import {
  SUBMISSION_START_TIME_CHANGED,
  SUBMISSION_END_TIME_CHANGED,
  SUBMISSION_STATUS_CHANGED,
  SUBMISSION_ERROR_MESSAGE_CHANGED
} from "../../components/lib/EventBusMessages"

function updateDataReducer(state, { action = "update", data }) {
  switch (action) {
    case "set":
      return data

    case "update":
      if (typeof state === "undefined" || state.id !== data.id) {
        return state
      }
      return { ...state, ...data }

    default:
      return state
  }
}

export default () => {
  const router = useRouter()
  const { id } = router.query

  const [data, updateData] = useReducer(updateDataReducer)
  const [error, setError] = useState()
  const eventBus = useContext(EventBusContext)

  useEffect(() => {
    if (id) {
      fetcher(`${process.env.baseUrl}/workflows/${id}`)
        .then(data => updateData({ action: "set", data }))
        .catch(err => {
          console.log(err)
          setError(<Alert error>Could not load workflow</Alert>)
        })
    }
  }, [id])

  useEffect(() => {
    function onStartTimeChanged(error, message) {
      updateData({
        data: {
          id: message.body.submissionId,
          startTime: message.body.startTime
        }
      })
    }

    function onEndTimeChanged(error, message) {
      updateData({
        data: {
          id: message.body.submissionId,
          endTime: message.body.endTime
        }
      })
    }

    function onStatusChanged(error, message) {
      updateData({
        data: {
          id: message.body.submissionId,
          status: message.body.status
        }
      })
    }

    function onErrorMessageChanged(error, message) {
      updateData({
        data: {
          id: message.body.submissionId,
          errorMessage: message.body.errorMessage
        }
      })
    }

    if (eventBus) {
      eventBus.registerHandler(SUBMISSION_START_TIME_CHANGED, onStartTimeChanged)
      eventBus.registerHandler(SUBMISSION_END_TIME_CHANGED, onEndTimeChanged)
      eventBus.registerHandler(SUBMISSION_STATUS_CHANGED, onStatusChanged)
      eventBus.registerHandler(SUBMISSION_ERROR_MESSAGE_CHANGED, onErrorMessageChanged)
      // eventBus.registerHandler(PROCESS_CHAIN_ALL_STATUS_CHANGED, onAllStatusChanged)
    }

    return () => {
      if (eventBus && eventBus.state === EventBus.OPEN) {
        // eventBus.unregisterHandler(PROCESS_CHAIN_ALL_STATUS_CHANGED, onAllStatusChanged)
        eventBus.unregisterHandler(SUBMISSION_ERROR_MESSAGE_CHANGED, onErrorMessageChanged)
        eventBus.unregisterHandler(SUBMISSION_STATUS_CHANGED, onStatusChanged)
        eventBus.unregisterHandler(SUBMISSION_END_TIME_CHANGED, onEndTimeChanged)
        eventBus.unregisterHandler(SUBMISSION_START_TIME_CHANGED, onStartTimeChanged)
      }
    }
  }, [eventBus])

  let breadcrumbs
  let title
  let workflow

  if (typeof data !== "undefined") {
    title = data.id
    breadcrumbs = [
      <Link href="/workflows" key="workflows"><a>Workflows</a></Link>,
      data.id
    ]

    let progress = workflowToProgress(data)

    workflow = (<>
      <div className="detail-header">
        <div className="detail-header-left">
          <DefinitionList>
            <DefinitionListItem title="Start time">
              {data.startTime ? formatDate(data.startTime) : <>&ndash;</>}
            </DefinitionListItem>
            <DefinitionListItem title="End time">
              {data.endTime ? formatDate(data.endTime) : <>&ndash;</>}
            </DefinitionListItem>
            <DefinitionListItem title="Time elapsed">
              {
                data.startTime && data.endTime ? formatDurationTitle(data.startTime, data.endTime) : (
                  data.startTime ? <LiveDuration startTime={data.startTime} /> : <>&ndash;</>
                )
              }
            </DefinitionListItem>
          </DefinitionList>
        </div>
        <div className="detail-header-right">
          <ListItemProgressBox progress={progress} />
        </div>
      </div>
      {data.errorMessage && (<>
        <h2>Error message</h2>
        <Alert error>{data.errorMessage}</Alert>
      </>)}
      <h2>Source</h2>
      <CodeBox json={data.workflow} />
    </>)
  }

  return (
    <DetailPage breadcrumbs={breadcrumbs} title={title}>
      {workflow}
      {error}
    </DetailPage>
  )
}
