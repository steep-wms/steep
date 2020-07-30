import { formatDurationTitle } from "./lib/date-time-utils"
import { useEffect, useState } from "react"

const LiveDuration = ({ startTime }) => {
  const [now, setNow] = useState(new Date())

  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 1000)
    return () => {
      clearInterval(timer)
    }
  }, [])

  return formatDurationTitle(startTime, now)
}

export default LiveDuration
