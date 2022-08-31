import DetailPage from "../../components/layouts/DetailPage"
import { useRouter } from "next/router"
import Alert from "../../components/Alert"
import DefinitionList from "../../components/DefinitionList"
import DefinitionListItem from "../../components/DefinitionListItem"
import Label from "../../components/Label"
import styles from "./[id].scss"
import { formatDurationMilliseconds } from "../../components/lib/date-time-utils"
import useSWRImmutable from "swr/immutable"
import fetcher from "../../components/lib/json-fetcher"

const Service = () => {
  const router = useRouter()
  const { id } = router.query
  const { data, error } = useSWRImmutable(() => id && `${process.env.baseUrl}/services/${id}`, fetcher)

  let title
  let subtitle
  let service
  let none = <span className="none"></span>

  if (data !== undefined) {
    title = data.name
    subtitle = data.description
    let reqcap
    if (data.requiredCapabilities === undefined || data.requiredCapabilities.length === 0) {
      reqcap = <>&ndash;</>
    } else {
      reqcap = data.requiredCapabilities.map((r, i) => <Label key={i}>{r}</Label>)
    }
    service = (<>
      <div className="service-details">
        <DefinitionList>
          <div className="service-details-dl">
            <div className="service-details-left">
              <DefinitionListItem title="ID">{data.id}</DefinitionListItem>
              <DefinitionListItem title="Required capabilities">{reqcap}</DefinitionListItem>
            </div>
            <div className="service-details-right">
              <DefinitionListItem title="Runtime"><Label>{data.runtime}</Label></DefinitionListItem>
              {data.runtime !== "docker" ? (
                <DefinitionListItem title="Path to executable">
                  <span className="service-details-value">{data.path}</span>
                </DefinitionListItem>
              ) : (
                <DefinitionListItem title="Docker image">
                  <span className="service-details-value">{data.path}</span>
                </DefinitionListItem>
              )}

            </div>
          </div>
        </DefinitionList>
      </div>

      {data.retries && (<>
        <h2>Retry policy</h2>
        <div className="retry-policy-details">
          <DefinitionList>
            <div className="service-details-dl">
              <div className="service-details-left">
                <DefinitionListItem title="Max. attempts">{data.retries.maxAttempts || 1}</DefinitionListItem>
                <DefinitionListItem title="Delay">{(data.retries.delay && formatDurationMilliseconds(data.retries.delay, true)) || "0ms"}</DefinitionListItem>
              </div>
              <div className="service-details-right">
                <DefinitionListItem title="Exponential backoff factor">{data.retries.exponentialBackoff || 1}</DefinitionListItem>
                <DefinitionListItem title="Maximum delay">{(data.retries.maxDelay && formatDurationMilliseconds(data.retries.maxDelay, true)) || <>&ndash;</>}</DefinitionListItem>
              </div>
            </div>
          </DefinitionList>
        </div>
      </>)}

      {(data.maxInactivity || data.maxRuntime || data.deadline) && (<>
        <h2>Timeout policy</h2>
        <div className="timeout-policy-details">
          <DefinitionList>
            <div className="service-details-dl">
              <div className="service-details-left">
                <DefinitionListItem title="Max. inactivity">
                  {data.maxInactivity && (<>
                    {data.maxInactivity.errorOnTimeout ? "Fail" : "Cancel"} after {formatDurationMilliseconds(data.maxInactivity.timeout, true)}
                  </>) || <>&ndash;</>}
                </DefinitionListItem>
                <DefinitionListItem title="Max. runtime">
                  {data.maxRuntime && (<>
                    {data.maxRuntime.errorOnTimeout ? "Fail" : "Cancel"} after {formatDurationMilliseconds(data.maxRuntime.timeout, true)}
                  </>) || <>&ndash;</>}
                </DefinitionListItem>
              </div>
              <div className="service-details-right">
                <DefinitionListItem title="Deadline">
                  {data.deadline && (<>
                    {data.deadline.errorOnTimeout ? "Fail" : "Cancel"} after {formatDurationMilliseconds(data.deadline.timeout, true)}
                  </>) || <>&ndash;</>}
                </DefinitionListItem>
              </div>
            </div>
          </DefinitionList>
        </div>
      </>)}

      {data.runtimeArgs && data.runtimeArgs.length > 0 && (<>
        <h2>Runtime arguments</h2>
        {data.runtimeArgs.map(r => (
          <div className="service-parameter" key={r.id}>
            <div className="service-parameter-left">
              <h4>{r.name}</h4>
              {r.description}
            </div>
            <div className="service-parameter-right">
              <h5>ID:</h5>
              <span className="service-parameter-value">{r.id}</span>

              <h5>Data type:</h5>
              <span className="service-parameter-value">{r.dataType}</span>

              <h5>Label:</h5>
              <span className="service-parameter-value">{r.label || none}</span>

              <h5>Value:</h5>
              <span className="service-parameter-value">{r.value || none}</span>
            </div>
          </div>
        ))}
      </>)}

      {data.parameters && data.parameters.length > 0 && (<>
        <h2>Parameters</h2>
        {data.parameters.map(p => (
          <div className="service-parameter" key={p.id}>
            <div className="service-parameter-left">
              <h4>{p.name}</h4>
              {p.description}
            </div>
            <div className="service-parameter-right">
              <h5>ID:</h5>
              <span className="service-parameter-value">{p.id}</span>

              <h5>Type:</h5>
              <span className="service-parameter-value">{p.type}</span>

              <h5>Cardinality:</h5>
              <span className="service-parameter-value">{p.cardinality}</span>

              <h5>Data type:</h5>
              <span className="service-parameter-value">{p.dataType}</span>

              <h5>Default value:</h5>
              <span className="service-parameter-value">{p.default === undefined ? none : "" + p.default}</span>

              <h5>File suffix:</h5>
              <span className="service-parameter-value">{p.fileSuffix || none}</span>

              <h5>Label:</h5>
              <span className="service-parameter-value">{p.label || none}</span>
            </div>
          </div>
        ))}
      </>)}
      <style jsx>{styles}</style>
    </>)
  }

  return (
    <DetailPage title={title} subtitle={subtitle}>
      {service}
      {error && <Alert error>Could not load service</Alert>}
      <style jsx>{styles}</style>
    </DetailPage>
  )
}

export default Service
