import dayjs from "dayjs"
import Duration from "dayjs/plugin/duration"
import RelativeTime from "dayjs/plugin/relativeTime"
dayjs.extend(Duration)
dayjs.extend(RelativeTime)

export function formatDate(date) {
  return dayjs(date).format("dddd, D MMMM YYYY, h:mm:ss a")
}

export function formatIsoLocalDateTime(date) {
  return dayjs(date).format("YYYY-MM-DDTHH:mm:ss")
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

// just like formatDurationTitle but also displays days and milliseconds. Does
// not have a startTime and an endTime, just a difference, but this can be
// easily added (see code of formatDurationTitle).
export function formatDurationMilliseconds(durationMilliseconds, trimRight) {
  let milliseconds = Math.floor(durationMilliseconds % 1000)
  let seconds = Math.floor(durationMilliseconds / 1000 % 60)
  let minutes = Math.floor(durationMilliseconds / 1000 / 60 % 60)
  let hours = Math.floor(durationMilliseconds / 1000 / 60 / 60 % 24)
  let days = Math.floor(durationMilliseconds / 1000 / 60 / 60 / 24)
  let result = ""
  if (days > 0) {
    result += days + "d "
  }
  if (!trimRight || hours > 0 || minutes > 0 || seconds > 0 || milliseconds > 0) {
    if (result !== "" || hours > 0) {
      result += hours + "h "
    }
    if (!trimRight || minutes > 0 || seconds > 0 || milliseconds > 0) {
      if (result !== "" || minutes > 0) {
        result += minutes + "m "
      }
      if (!trimRight || seconds > 0 || milliseconds > 0) {
        if (result !== "" || seconds > 0) {
          result += seconds + "s "
        }
        if (!trimRight || milliseconds > 0) {
          result += milliseconds + "ms"
        }
      }
    }
  }
  if (trimRight && result === "") {
    result = "0ms"
  }
  return result
}
