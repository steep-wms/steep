import { createContext, useEffect, useReducer } from "react"
import { produce } from "immer"

const LOCAL_STORAGE_KEY = "SteepSettings"

const State = createContext()
const Dispatch = createContext()

const reducer = produce((draft, { theme }) => {
  if (theme !== undefined) {
    draft.theme = theme
  }
})

function applyTheme(name) {
  document.body.setAttribute("data-theme", name)
  setTimeout(() => {
    document.body.setAttribute("data-theme-transition", "true")
  }, 100)
}

const Provider = ({ children }) => {
  const [state, dispatch] = useReducer(reducer, {
    theme: "default"
  })

  useEffect(() => {
    if (typeof localStorage !== "undefined") {
      let r = localStorage.getItem(LOCAL_STORAGE_KEY)
      if (r !== undefined && r !== null) {
        r = JSON.parse(r)
        dispatch(r)
      }
    }
  }, [])

  useEffect(() => {
    if (typeof localStorage !== "undefined") {
      localStorage.setItem(LOCAL_STORAGE_KEY, JSON.stringify(state))
    }

    applyTheme(state.theme)
  }, [state])

  return (
    <State.Provider value={state}>
      <Dispatch.Provider value={dispatch}>{children}</Dispatch.Provider>
    </State.Provider>
  )
}

const SettingsContext = {
  State,
  Dispatch,
  Provider
}

export default SettingsContext
