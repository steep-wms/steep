import NProgress from "nprogress"
import fetch from "unfetch"

NProgress.configure({ showSpinner: false })

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
        body: await r.json()
      }
    } else {
      return await r.json()
    }
  } finally {
    clearTimeout(timer)
    NProgress.done()
  }
}

export default fetcher
