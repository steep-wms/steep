import dayjs from "dayjs"
import Duration from "dayjs/plugin/duration"
import RelativeTime from "dayjs/plugin/relativeTime"
dayjs.extend(Duration)
dayjs.extend(RelativeTime)

export function formatDate(date) {
  return dayjs(date).format("dddd, D MMMM YYYY, h:mm:ss a")
}

export function formatDuration(startTime, endTime) {
  let diff = dayjs(endTime).diff(dayjs(startTime))
  return dayjs.duration(diff).humanize()
}

export function formatDurationTitle(startTime, endTime) {
  let diff = dayjs(endTime).diff(dayjs(startTime))
  let duration = Math.ceil(dayjs.duration(diff).asSeconds())
  let seconds = Math.floor(duration % 60)
  let minutes = Math.floor(duration / 60 % 60)
  let hours = Math.floor(duration / 60 / 60)
  let result = ""
  if (hours > 0) {
    result += hours + "h "
  }
  if (result !== "" || minutes > 0) {
    result += minutes + "m "
  }
  result += seconds + "s"
  return result
}
