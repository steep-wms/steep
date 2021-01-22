import Alert from "./Alert"
import Code from "./Code"
import fetcher from "./lib/json-fetcher"
import { useEffect, useRef, useState } from "react"

const ProcessChainLog = ({ id }) => {
  const ref = useRef()
  const [contents, setContents] = useState()
  const [error, setError] = useState()

  useEffect(() => {
    if (id === undefined) {
      return
    }

    let options = {
      headers: {
        "accept": "text/plain"
      }
    }

    async function handleResponse(r) {
      let body = await r.text()
      if (r.status === 404) {
        return undefined
      } else if (r.status !== 200) {
        throw new Error(body)
      }
      return body
    }

    fetcher(`${process.env.baseUrl}/logs/processchains/${id}`, false, options, handleResponse)
        .then(log => {
          if (log === undefined) {
            setError(<Alert error>
              <p>Unable to find process chain logs</p>
              <p>This can have several reasons:</p>
              <ul>
                <li>The process chain has not produced any output (yet)</li>
                <li>The agent that has executed the process chain is not available anymore</li>
                <li>Process chain logging is disabled in Steep&rsquo;s configuration</li>
              </ul>
            </Alert>)
          }
          setContents(log)
          setTimeout(() => {
            ref.current.scrollTop = ref.current.scrollHeight
          }, 0)
        })
        .catch(err => {
          console.log(err)
          setError(<Alert error>Could not load process chain</Alert>)
        })
  }, [id])

  return (<>
    {error}
    {contents && <Code lang="log" ref={ref}>{contents}</Code>}
  </>)
}

export default ProcessChainLog
