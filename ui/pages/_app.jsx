import EventBusContext from "../components/lib/EventBusContext"
import EventBus from "@vertx/eventbus-bridge-client.js"
import { useEffect, useState } from "react"
import styles from "../css/main.scss?type=global"

const App = ({ Component, pageProps }) => {
  const [eventBus, setEventBus] = useState()

  useEffect(() => {
    let eb = new EventBus(process.env.baseUrl + "/eventbus")
    eb.enableReconnect(true)
    eb.onopen = () => {
      setEventBus(eb)
    }

    return () => {
      eb.close()
    }
  }, [])

  return (
    <EventBusContext.Provider value={eventBus}>
      <Component {...pageProps} />
      <style jsx>{styles}</style>
    </EventBusContext.Provider>
  )
}

export default App
