import "../css/main.scss"
import EventBusContext from "../components/lib/EventBusContext"
import EventBus from "vertx3-eventbus-client"
import { useEffect, useState } from "react"

export default ({ Component, pageProps }) => {
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
    </EventBusContext.Provider>
  )
}
