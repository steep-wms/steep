import fetch from "unfetch"

export default async (url) => {
  let r = await fetch(url, {
    headers: {
      "accept": "application/json"
    }
  })
  return r.json()
}
