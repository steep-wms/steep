import EventBusContext from "../lib/EventBusContext"
import EventBus from "vertx3-eventbus-client"
import { createContext, useContext, useEffect, useReducer } from "react"
import listItemUpdateReducer from "../lib/listitem-update-reducer"
import fetcher from "../../components/lib/json-fetcher"

import {
  AGENT_ADDRESS_PREFIX,
  AGENT_ADDED,
  AGENT_LEFT,
  AGENT_BUSY,
  AGENT_IDLE
} from "../../components/lib/EventBusMessages"

const State = createContext()
const Dispatch = createContext()

function updateAgentsReducer(onAgentChanged) {
  let liur = listItemUpdateReducer(undefined, (processChain) => {
    onAgentChanged && onAgentChanged(processChain)
  })

  return (state, { action = "unshift", agents }) => {
    state = state || []
    switch (action) {
      case "set":
      case "update":
      case "unshift":
      case "push":
        return liur(state, { action, items: agents })

      default:
        return state
    }
  }
}

const Provider = ({ onAgentChanged, allowAdd = true, children }) => {
  const [agents, updateAgents] = useReducer(updateAgentsReducer(onAgentChanged))
  const eventBus = useContext(EventBusContext)

  useEffect(() => {
    function onAgentAdded(error, message) {
      let id = message.body.substring(AGENT_ADDRESS_PREFIX.length)
      fetcher(`${process.env.baseUrl}/agents/${id}`)
        .then(agent => {
          agent.justAdded = true
          updateAgents({ action: "unshift", agents: [agent] })
        })
        .catch(err => console.error(err))
    }

    function onAgentLeft(error, message) {
      let id = message.body.substring(AGENT_ADDRESS_PREFIX.length)
      updateAgents({
        action: "update", agents: [{
          id,
          left: true,
          stateChangedTime: new Date()
        }]
      })
    }

    function onAgentBusy(error, message) {
      let id = message.body.substring(AGENT_ADDRESS_PREFIX.length)
      updateAgents({
        action: "update", agents: [{
          id,
          available: false,
          stateChangedTime: new Date()
        }]
      })
    }

    function onAgentIdle(error, message) {
      let id = message.body.substring(AGENT_ADDRESS_PREFIX.length)
      updateAgents({
        action: "update", agents: [{
          id,
          available: true,
          stateChangedTime: new Date()
        }]
      })
    }

    if (eventBus) {
      if (allowAdd) {
        eventBus.registerHandler(AGENT_ADDED, onAgentAdded)
      }
      eventBus.registerHandler(AGENT_LEFT, onAgentLeft)
      eventBus.registerHandler(AGENT_BUSY, onAgentBusy)
      eventBus.registerHandler(AGENT_IDLE, onAgentIdle)
    }

    return () => {
      if (eventBus && eventBus.state === EventBus.OPEN) {
        eventBus.unregisterHandler(AGENT_IDLE, onAgentIdle)
        eventBus.unregisterHandler(AGENT_BUSY, onAgentBusy)
        eventBus.unregisterHandler(AGENT_LEFT, onAgentLeft)
        if (allowAdd) {
          eventBus.unregisterHandler(AGENT_ADDED, onAgentAdded)
        }
      }
    }
  }, [eventBus, allowAdd])

  return (
    <State.Provider value={agents}>
      <Dispatch.Provider value={updateAgents}>{children}</Dispatch.Provider>
    </State.Provider>
  )
}

export default {
  State,
  Dispatch,
  Provider
}
