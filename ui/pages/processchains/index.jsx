import EventBusContext from "../../components/lib/EventBusContext"
import EventBus from "vertx3-eventbus-client"
import Page from "../../components/layouts/Page"
import Alert from "../../components/Alert"
import ListItem from "../../components/ListItem"
import { useContext, useEffect, useReducer, useState } from "react"
import fetcher from "../../components/lib/json-fetcher"
import listItemUpdateReducer from "../../components/lib/listitem-update-reducer"

import {
  PROCESS_CHAINS_ADDED,
  PROCESS_CHAIN_START_TIME_CHANGED,
  PROCESS_CHAIN_END_TIME_CHANGED,
  PROCESS_CHAIN_STATUS_CHANGED,
  PROCESS_CHAIN_ALL_STATUS_CHANGED,
  PROCESS_CHAIN_ERROR_MESSAGE_CHANGED
} from "../../components/lib/EventBusMessages"

function initProcessChain(processChain) {
  delete processChain.executables
  processChain.startTime = processChain.startTime || null
  processChain.endTime = processChain.endTime || null
}

function processChainToElement(processChain) {
  let href = `/processchains/${processChain.id}`

  let progress = {
    status: processChain.status
  }

  return (
    <ListItem key={processChain.id} justAdded={processChain.justAdded}
      linkHref={href} title={processChain.id} startTime={processChain.startTime}
      endTime={processChain.endTime} progress={progress} />
  )
}

function updateProcessChainsReducer(pageSize) {
  let liur = listItemUpdateReducer(pageSize, (processChain) => {
    initProcessChain(processChain)
    processChain.element = processChainToElement(processChain)
  })

  return (state, { action = "unshift", processChains }) => {
    switch (action) {
      case "update":
      case "unshift":
      case "push":
        return liur(state, { action, items: processChains })

      case "updateStatus": {
        for (let update of processChains) {
          for (let i = 0; i < status.length; ++i) {
            let pc = status[i]
            if (pc.submissionId === update.submissionId && pc.status === update.currentStatus) {
              let newProcessChain = { ...pc, status: update.newStatus }
              state = [...state.slice(0, i), newProcessChain, ...state.slice(i + 1)]
            }
          }
        }
        return state
      }

      default:
        return state
    }
  }
}

export default () => {
  // parse query params but do not use "next/router" because router.query
  // is empty on initial render
  let pageSize
  if (typeof window !== "undefined") {
    let params = new URLSearchParams(window.location.search)
    pageSize = params.get("size") || 10
  }

  const [processChains, updateProcessChains] = useReducer(updateProcessChainsReducer(pageSize), [])
  const eventBus = useContext(EventBusContext)
  const [error, setError] = useState()

  useEffect(() => {
    fetcher(`${process.env.baseUrl}/processchains?size=${pageSize}`)
      .then(processChains => updateProcessChains({ action: "push", processChains }))
      .catch(err => {
        console.error(err)
        setError(<Alert error>Could not load process chains</Alert>)
      })
  }, [pageSize])

  useEffect(() => {
    function onProcessChainsAdded(error, message) {
      let processChains = message.body.processChains
      let status = message.body.status
      for (let processChain of processChains) {
        processChain.status = status
        initProcessChain(processChain)
        processChain.justAdded = true
      }
      updateProcessChains({ action: "unshift", processChains })
    }

    function onProcessChainStartTimeChanged(error, message) {
      updateProcessChains({
        action: "update", processChains: [{
          id: message.body.processChainId,
          startTime: message.body.startTime
        }]
      })
    }

    function onProcessChainEndTimeChanged(error, message) {
      updateProcessChains({
        action: "update", processChains: [{
          id: message.body.processChainId,
          endTime: message.body.endTime
        }]
      })
    }

    function onProcessChainStatusChanged(error, message) {
      updateProcessChains({
        action: "update", processChains: [{
          id: message.body.processChainId,
          status: message.body.status
        }]
      })
    }

    function onProcessChainAllStatusChanged(error, message) {
      updateProcessChains({
        action: "updateStatus", processChains: [{
          submissionId: message.body.submissionId,
          currentStatus: message.body.currentStatus,
          newStatus: message.body.newStatus
        }]
      })
    }

    function onProcessChainErrorMessageChanged(error, message) {
      updateProcessChains({
        action: "update", processChains: [{
          id: message.body.processChainId,
          errorMessage: message.body.errorMessage
        }]
      })
    }

    if (eventBus) {
      eventBus.registerHandler(PROCESS_CHAINS_ADDED, onProcessChainsAdded)
      eventBus.registerHandler(PROCESS_CHAIN_START_TIME_CHANGED, onProcessChainStartTimeChanged)
      eventBus.registerHandler(PROCESS_CHAIN_END_TIME_CHANGED, onProcessChainEndTimeChanged)
      eventBus.registerHandler(PROCESS_CHAIN_STATUS_CHANGED, onProcessChainStatusChanged)
      eventBus.registerHandler(PROCESS_CHAIN_ALL_STATUS_CHANGED, onProcessChainAllStatusChanged)
      eventBus.registerHandler(PROCESS_CHAIN_ERROR_MESSAGE_CHANGED, onProcessChainErrorMessageChanged)
    }

    return () => {
      if (eventBus && eventBus.state === EventBus.OPEN) {
        eventBus.unregisterHandler(PROCESS_CHAIN_ERROR_MESSAGE_CHANGED, onProcessChainErrorMessageChanged)
        eventBus.unregisterHandler(PROCESS_CHAIN_ALL_STATUS_CHANGED, onProcessChainAllStatusChanged)
        eventBus.unregisterHandler(PROCESS_CHAIN_STATUS_CHANGED, onProcessChainStatusChanged)
        eventBus.unregisterHandler(PROCESS_CHAIN_END_TIME_CHANGED, onProcessChainEndTimeChanged)
        eventBus.unregisterHandler(PROCESS_CHAIN_START_TIME_CHANGED, onProcessChainStartTimeChanged)
        eventBus.unregisterHandler(PROCESS_CHAINS_ADDED, onProcessChainsAdded)
      }
    }
  }, [eventBus])

  return (
    <Page>
      <h1>Process chains</h1>
      {processChains.map(pc => pc.element)}
      {error}
    </Page>
  )
}
