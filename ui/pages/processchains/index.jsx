import EventBusContext from "../../components/lib/EventBusContext"
import EventBus from "vertx3-eventbus-client"
import Page from "../../components/layouts/Page"
import Alert from "../../components/Alert"
import ListItem from "../../components/ListItem"
import { useContext, useEffect, useReducer, useRef } from "react"
import useSWR from "swr"
import fetcher from "../../components/lib/json-fetcher"

import {
  PROCESS_CHAINS_ADDED,
  PROCESS_CHAIN_START_TIME_CHANGED,
  PROCESS_CHAIN_END_TIME_CHANGED,
  PROCESS_CHAIN_STATUS_CHANGED,
  PROCESS_CHAIN_ALL_STATUS_CHANGED,
  PROCESS_CHAIN_ERROR_MESSAGE_CHANGED
} from "../../components/lib/EventBusMessages"

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

function updateProcessChainsReducer(state, { action = "unshift", processChains }) {
  switch (action) {
    case "update": {
      for (let processChain of processChains) {
        let i = state.findIndex(pc => pc.id === processChain.id)
        if (i >= 0) {
          let newProcessChain = { ...state[i], ...processChain }
          newProcessChain.element = processChainToElement(newProcessChain)
          state = [...state.slice(0, i), newProcessChain, ...state.slice(i + 1)]
        }
      }
      return state
    }

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

    case "unshift":
    case "push": {
      let processChainsToAdd = []
      for (let processChain of processChains) {
        if (state.findIndex(pc => pc.id === processChain.id) < 0) {
          processChain.element = processChainToElement(processChain)
          processChainsToAdd.push(processChain)
        }
      }

      if (action === "push") {
        return [...state, ...processChainsToAdd]
      } else {
        processChainsToAdd.reverse()
        return [...processChainsToAdd, ...state]
      }
    }

    default:
      return state
  }
}

export default () => {
  const [processChains, updateProcessChains] = useReducer(updateProcessChainsReducer, [])
  const eventBus = useContext(EventBusContext)
  const { data: fetchedProcessChains, error: fetchedProcessChainsError } =
      useSWR(process.env.baseUrl + "/processchains", fetcher)
  const oldFetchedProcessChains = useRef()

  function initProcessChain(processChain) {
    delete processChain.executables
    processChain.startTime = processChain.startTime || null
    processChain.endTime = processChain.endTime || null
  }

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

  let processChainError

  if (typeof fetchedProcessChainsError !== "undefined") {
    processChainError = <Alert error>Could not load process chains</Alert>
    console.error(fetchedProcessChainsError)
  } else if (typeof fetchedProcessChains !== "undefined") {
    if (fetchedProcessChains !== oldFetchedProcessChains.current) {
      oldFetchedProcessChains.current = fetchedProcessChains
      for (let processChain of fetchedProcessChains) {
        initProcessChain(processChain)
      }
      updateProcessChains({ action: "push", processChains: fetchedProcessChains })
    }
  }

  return (
    <Page>
      <h1>Process chains</h1>
      {processChains.map(pc => pc.element)}
      {processChainError}
    </Page>
  )
}
