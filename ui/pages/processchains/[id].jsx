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
import Label from "../../components/Label"
import ListItemProgressBox from "../../components/ListItemProgressBox"
import LiveDuration from "../../components/LiveDuration"
import "./ProcessChain.scss"
import { formatDate, formatDurationTitle } from "../../components/lib/date-time-utils"
import fetcher from "../../components/lib/json-fetcher"

import {
  PROCESS_CHAIN_START_TIME_CHANGED,
  PROCESS_CHAIN_END_TIME_CHANGED,
  PROCESS_CHAIN_STATUS_CHANGED,
  PROCESS_CHAIN_ALL_STATUS_CHANGED,
  PROCESS_CHAIN_ERROR_MESSAGE_CHANGED
} from "../../components/lib/EventBusMessages"

function updateDataReducer(state, { action = "update", data }) {
  switch (action) {
    case "set":
      return data

    case "updateAllStatus":
      if (typeof state === "undefined" || state.submissionId !== data.submissionId ||
          state.status !== data.currentStatus) {
        return state
      }
      return { ...state, status: data.newStatus }

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
      fetcher(`${process.env.baseUrl}/processchains/${id}`)
        .then(data => updateData({ action: "set", data }))
        .catch(err => {
          console.log(err)
          setError(<Alert error>Could not load process chain</Alert>)
        })
    }
  }, [id])

  useEffect(() => {
    function onStartTimeChanged(error, message) {
      updateData({
        data: {
          id: message.body.processChainId,
          startTime: message.body.startTime
        }
      })
    }

    function onEndTimeChanged(error, message) {
      updateData({
        data: {
          id: message.body.processChainId,
          endTime: message.body.endTime
        }
      })
    }

    function onStatusChanged(error, message) {
      updateData({
        data: {
          id: message.body.processChainId,
          status: message.body.status
        }
      })
    }

    function onAllStatusChanged(error, message) {
      updateData({
        action: "updateAllStatus",
        data: {
          submissionId: message.body.submissionId,
          currentStatus: message.body.currentStatus,
          newStatus: message.body.newStatus
        }
      })
    }

    function onErrorMessageChanged(error, message) {
      updateData({
        data: {
          id: message.body.processChainId,
          errorMessage: message.body.errorMessage
        }
      })
    }

    if (eventBus) {
      eventBus.registerHandler(PROCESS_CHAIN_START_TIME_CHANGED, onStartTimeChanged)
      eventBus.registerHandler(PROCESS_CHAIN_END_TIME_CHANGED, onEndTimeChanged)
      eventBus.registerHandler(PROCESS_CHAIN_STATUS_CHANGED, onStatusChanged)
      eventBus.registerHandler(PROCESS_CHAIN_ALL_STATUS_CHANGED, onAllStatusChanged)
      eventBus.registerHandler(PROCESS_CHAIN_ERROR_MESSAGE_CHANGED, onErrorMessageChanged)
    }

    return () => {
      if (eventBus && eventBus.state === EventBus.OPEN) {
        eventBus.unregisterHandler(PROCESS_CHAIN_ERROR_MESSAGE_CHANGED, onErrorMessageChanged)
        eventBus.unregisterHandler(PROCESS_CHAIN_ALL_STATUS_CHANGED, onAllStatusChanged)
        eventBus.unregisterHandler(PROCESS_CHAIN_STATUS_CHANGED, onStatusChanged)
        eventBus.unregisterHandler(PROCESS_CHAIN_END_TIME_CHANGED, onEndTimeChanged)
        eventBus.unregisterHandler(PROCESS_CHAIN_START_TIME_CHANGED, onStartTimeChanged)
      }
    }
  }, [eventBus])

  let breadcrumbs
  let title
  let processchain

  if (typeof data !== "undefined") {
    title = data.id
    breadcrumbs = [
      <Link href="/workflows" key="workflows"><a>Workflows</a></Link>,
      <Link href="/workflows/[id]" as={`/workflows/${data.submissionId}`} key={data.submissionId}>
        <a>{data.submissionId}</a>
      </Link>,
      <Link href="/processchains" key="processchains"><a>Process chains</a></Link>,
      data.id
    ]

    let reqcap
    if (typeof data.requiredCapabilities === "undefined" || data.requiredCapabilities.length === 0) {
      reqcap = <>&ndash;</>
    } else {
      reqcap = data.requiredCapabilities.map((r, i) => <Label key={i}>{r}</Label>)
    }

    let progress = {
      status: data.status
    }

    processchain =
      <div className="processchain-details">
        <div className="processchain-details-header">
          <div className="processchain-details-left">
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
              <DefinitionListItem title="Required capabilities">
                {reqcap}
              </DefinitionListItem>
            </DefinitionList>
          </div>
          <div className="processchain-details-right">
            <ListItemProgressBox progress={progress} />
          </div>
        </div>
        {data.errorMessage && (<>
          <h3>Error message</h3>
          <Alert error>{data.errorMessage}</Alert>
        </>)}
        <h3>Executables</h3>
        <CodeBox json={data.executables} />
      </div>
  }

  return (
    <DetailPage breadcrumbs={breadcrumbs} title={title}>
      {processchain}
      {error}
    </DetailPage>
  )
}
