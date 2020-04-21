import { formatDurationTitle } from "./lib/date-time-utils"
import { useEffect, useState } from "react"

export default ({ startTime }) => {
  const [now, setNow] = useState(new Date())

  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 1000)
    return () => {
      clearInterval(timer)
    }
  }, [])

  return formatDurationTitle(startTime, now)
}
