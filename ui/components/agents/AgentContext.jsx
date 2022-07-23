import ListContext from "../lib/ListContext"
import fetcher from "../lib/json-fetcher"

import {
  CLUSTER_NODE_LEFT,
  AGENT_ADDED,
  AGENT_LEFT,
  AGENT_BUSY,
  AGENT_IDLE
} from "../../components/lib/EventBusMessages"

const ADD_MESSAGES = {
  [AGENT_ADDED]: (body) => {
    return fetcher(`${process.env.baseUrl}/agents/${body}`).then(agent => [agent])
  }
}

const UPDATE_MESSAGES = {
  [AGENT_ADDED]: (body) => ({
    id: body,
    left: false, // make agent visible again if it's already in the list
    processChainId: undefined,
    stateChangedTime: new Date()
  }),
  [CLUSTER_NODE_LEFT]: (body) => {
    let agentId = body.agentId
    let instances = body.instances || 1
    let r = []
    for (let i = 1; i <= instances; ++i) {
      let id = i === 1 ? agentId : `${agentId}[${i}]`
      r.push({
        id,
        left: true,
        stateChangedTime: new Date()
      })
    }
    return r
  },
  [AGENT_LEFT]: (body) => ({
    id: body,
    left: true,
    processChainId: undefined,
    stateChangedTime: new Date()
  }),
  [AGENT_BUSY]: (body) => ({
    id: body.id,
    available: false,
    processChainId: body.processChainId,
    stateChangedTime: new Date(body.stateChangedTime)
  }),
  [AGENT_IDLE]: (body) => ({
    id: body.id,
    available: true,
    processChainId: undefined,
    stateChangedTime: new Date(body.stateChangedTime)
  })
}

const Provider = (props) => (
  <ListContext.Provider {...props} addMessages={ADD_MESSAGES}
      updateMessages={UPDATE_MESSAGES} />
)

const AgentContext = {
  Items: ListContext.Items,
  UpdateItems: ListContext.UpdateItems,
  AddedItems: ListContext.AddedItems,
  UpdateAddedItems: ListContext.UpdateAddedItems,
  Provider
}

export default AgentContext
