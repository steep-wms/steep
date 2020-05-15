import DetailPage from "../../components/layouts/DetailPage"
import { useRouter } from "next/router"
import { useEffect, useState } from "react"
import Alert from "../../components/Alert"
import DefinitionList from "../../components/DefinitionList"
import DefinitionListItem from "../../components/DefinitionListItem"
import Label from "../../components/Label"
import "./[id].scss"
import fetcher from "../../components/lib/json-fetcher"

export default () => {
  const router = useRouter()
  const { id } = router.query

  const [data, setData] = useState()
  const [error, setError] = useState()

  useEffect(() => {
    if (id) {
      fetcher(`${process.env.baseUrl}/services/${id}`)
        .then(setData)
        .catch(err => {
          console.log(err)
          setError(<Alert error>Could not load service</Alert>)
        })
    }
  }, [id])

  let title
  let subtitle
  let service
  let none = <span className="none"></span>

  if (data !== undefined) {
    title = data.name
    subtitle = data.description
    let reqcap
    if (data.required_capabilities === undefined || data.required_capabilities.length === 0) {
      reqcap = <>&ndash;</>
    } else {
      reqcap = data.required_capabilities.map((r, i) => <Label key={i}>{r}</Label>)
    }
    service = (<>
      <div className="service-details">
        <DefinitionList>
          <div className="service-details-left">
            <DefinitionListItem title="ID">{data.id}</DefinitionListItem>
            <DefinitionListItem title="Required capabilities">{reqcap}</DefinitionListItem>
          </div>
          <div className="service-details-right">
            <DefinitionListItem title="Runtime"><Label>{data.runtime}</Label></DefinitionListItem>
            {data.runtime !== "docker" ? (
              <DefinitionListItem title="Path to executable">{data.path}</DefinitionListItem>
            ) : (
              <DefinitionListItem title="Docker image">{data.path}</DefinitionListItem>
            )}

          </div>
        </DefinitionList>
      </div>

      {data.runtime_args && data.runtime_args.length > 0 && (<>
        <h2>Runtime arguments</h2>
        {data.runtime_args.map(r => (
          <div className="service-parameter" key={r.id}>
            <div className="service-parameter-left">
              <h4>{r.name}</h4>
              {r.description}
            </div>
            <div className="service-parameter-right">
              <h5>ID:</h5>
              <span className="service-parameter-value">{r.id}</span>

              <h5>Data type:</h5>
              <span className="service-parameter-value">{r.data_type}</span>

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
              <span className="service-parameter-value">{p.data_type}</span>

              <h5>Default value:</h5>
              <span className="service-parameter-value">{p.default === undefined ? none : "" + p.default}</span>

              <h5>File suffix:</h5>
              <span className="service-parameter-value">{p.file_suffix || none}</span>

              <h5>Label:</h5>
              <span className="service-parameter-value">{p.label || none}</span>
            </div>
          </div>
        ))}
      </>)}
    </>)
  }

  return (
    <DetailPage title={title} subtitle={subtitle}>
      {service}
      {error}
    </DetailPage>
  )
}
