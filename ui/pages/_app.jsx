import EventBusContext from "../components/lib/EventBusContext"
import EventBus from "@vertx/eventbus-bridge-client.js"
import { useEffect, useState } from "react"
import styles from "../css/main.scss?type=global"
import SettingsContext from "../components/lib/SettingsContext"

const App = ({ Component, pageProps }) => {
  const [eventBus, setEventBus] = useState()

  useEffect(() => {
    let eb = new EventBus(process.env.baseUrl + "/eventbus")
    eb.enableReconnect(true)
    eb.onopen = () => {
      setEventBus(eb)
    }
    eb.onclose = () => {
      setEventBus(undefined)
    }

    return () => {
      try {
        eb.close()
      } catch (e) {
        console.warn("Could not close event bus", e)
      }
    }
  }, [])

  return (
    <EventBusContext.Provider value={eventBus}>
      <SettingsContext.Provider>
        <Component {...pageProps} />
      </SettingsContext.Provider>
      <style jsx>{styles}</style>
    </EventBusContext.Provider>
  )
}

export default App
