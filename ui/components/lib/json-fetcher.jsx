import NProgress from "nprogress"
import fetch from "unfetch"
import "./json-fetcher.scss"

NProgress.configure({ showSpinner: false })

export default async (url, withHeaders = false) => {
  let timer = setTimeout(NProgress.start, 100)
  try {
    let r = await fetch(url, {
      headers: {
        "accept": "application/json"
      }
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
