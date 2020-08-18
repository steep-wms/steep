import NProgress from "nprogress"
import fetch from "unfetch"

NProgress.configure({ showSpinner: false })

async function handleResponse(r) {
  if (r.headers.get("content-type") === "application/json") {
    return await r.json()
  }
  return await r.text()
}

async function fetcher(url, withHeaders = false, options = {}) {
  let timer = setTimeout(NProgress.start, 100)
  try {
    let r = await fetch(url, {
      headers: {
        "accept": "application/json"
      },
      ...options
    })
    if (withHeaders) {
      return {
        headers: r.headers,
        status: r.status,
        body: await handleResponse(r)
      }
    } else {
      return await handleResponse(r)
    }
  } finally {
    clearTimeout(timer)
    NProgress.done()
  }
}

export default fetcher
