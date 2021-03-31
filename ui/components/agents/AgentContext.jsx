import ListContext from "../lib/ListContext"
import fetcher from "../lib/json-fetcher"

import {
  AGENT_ADDRESS_PREFIX,
  AGENT_ADDED,
  AGENT_LEFT,
  AGENT_BUSY,
  AGENT_IDLE
} from "../../components/lib/EventBusMessages"

const ADD_MESSAGES = {
  [AGENT_ADDED]: (body) => {
    let id = body.substring(AGENT_ADDRESS_PREFIX.length)
    return fetcher(`${process.env.baseUrl}/agents/${id}`).then(agent => [agent])
  }
}

const UPDATE_MESSAGES = {
  [AGENT_LEFT]: (body) => ({
    id: body.substring(AGENT_ADDRESS_PREFIX.length),
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

export default {
  Items: ListContext.Items,
  UpdateItems: ListContext.UpdateItems,
  AddedItems: ListContext.AddedItems,
  UpdateAddedItems: ListContext.UpdateAddedItems,
  Provider
}
