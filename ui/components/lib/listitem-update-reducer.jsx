export default (pageSize, initItem) => (state, { action = "unshift", items }) => {
  if (action === "set") {
    action = "push"
    state = []
  }

  switch (action) {
    case "update": {
      for (let item of items) {
        let i = state.findIndex(w => w.id === item.id)
        if (i >= 0) {
          let newItem = { ...state[i], ...item }
          initItem(newItem)
          state = [...state.slice(0, i), newItem, ...state.slice(i + 1)]
        }
      }
      return state
    }

    case "unshift":
    case "push": {
      if (action === "push" && typeof pageSize !== "undefined" && state.length >= pageSize) {
        return state
      }

      let itemsToAdd = []
      for (let item of items) {
        if (state.findIndex(w => w.id === item.id) < 0) {
          itemsToAdd.push(item)
        }
      }

      if (action === "push") {
        if (typeof pageSize !== "undefined") {
          itemsToAdd = itemsToAdd.slice(0, pageSize - state.length)
        }
      } else {
        itemsToAdd.reverse()
        if (typeof pageSize !== "undefined") {
          itemsToAdd = itemsToAdd.slice(0, pageSize)
          state = state.slice(0, pageSize - itemsToAdd.length)
        }
      }

      for (let item of itemsToAdd) {
        initItem(item)
      }

      if (action === "push") {
        return [...state, ...itemsToAdd]
      } else {
        return [...itemsToAdd, ...state]
      }
    }

    default:
      return state
  }
}
