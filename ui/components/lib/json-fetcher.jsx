import NProgress from "nprogress"

NProgress.configure({ showSpinner: false })

async function defaultHandleResponse(r) {
  if (r.headers.get("content-type") === "application/json") {
    return await r.json()
  }
  return await r.text()
}

async function fetcher(url, withHeaders = false, options = {},
    handleResponse = undefined) {
  let timer = setTimeout(NProgress.start, 100)
  try {
    let r = await fetch(url, {
      headers: {
        "accept": "application/json"
      },
      ...options
    })

    let body
    if (handleResponse === undefined) {
      body = await defaultHandleResponse(r)
      if (r.status < 200 || r.status >= 300) {
        if (typeof body === "object") {
          throw new Error(JSON.stringify(body))
        }
        throw new Error(body)
      }
    } else {
      body = await handleResponse(r)
    }

    if (withHeaders) {
      return {
        headers: r.headers,
        body
      }
    } else {
      return body
    }
  } finally {
    clearTimeout(timer)
    NProgress.done()
  }
}

export default fetcher
