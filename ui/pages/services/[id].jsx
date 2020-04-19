import DetailPage from "../../components/layouts/DetailPage"
import { useRouter } from "next/router"
import Alert from "../../components/Alert"
import DefinitionList from "../../components/DefinitionList"
import DefinitionListItem from "../../components/DefinitionListItem"
import Label from "../../components/Label"
import "./Service.scss"
import useSWR from "swr"
import fetcher from "../../components/lib/json-fetcher"

export default () => {
  const router = useRouter()
  const { id } = router.query

  const { data, error } = useSWR(id && `${process.env.baseUrl}/services/${id}`, fetcher)

  let errorMessage
  let title
  let subtitle
  let service
  let none = <span className="none"></span>

  if (typeof error !== "undefined") {
    console.log(error)
    errorMessage = <Alert error>Could not load service</Alert>
  } else if (typeof data !== "undefined") {
    title = data.name
    subtitle = data.description
    let reqcap
    if (typeof data.required_capabilities === "undefined" || data.required_capabilities.length === 0) {
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
        <h3>Runtime arguments</h3>
        {data.runtime_args.map(r => (
          <div className="service-parameter" key={r.id}>
            <div className="service-parameter-left">
              <h4>{r.name}</h4>
              {r.description}
            </div>
            <div className="service-parameter-right">
              <h5>ID:</h5>
              {r.id}

              <h5>Data type:</h5>
              {r.data_type}

              <h5>Label:</h5>
              {r.label || none}

              <h5>Value:</h5>
              {r.value || none}
            </div>
          </div>
        ))}
      </>)}

      {data.parameters && data.parameters.length > 0 && (<>
        <h3>Parameters</h3>
        {data.parameters.map(p => (
          <div className="service-parameter" key={p.id}>
            <div className="service-parameter-left">
              <h4>{p.name}</h4>
              {p.description}
            </div>
            <div className="service-parameter-right">
              <h5>ID:</h5>
              {p.id}

              <h5>Type:</h5>
              {p.type}

              <h5>Cardinality:</h5>
              {p.cardinality}

              <h5>Data type:</h5>
              {p.data_type}

              <h5>Default value:</h5>
              {p.default || none}

              <h5>File suffix:</h5>
              {p.file_suffix || none}

              <h5>Label:</h5>
              {p.label || none}
            </div>
          </div>
        ))}
      </>)}
    </>)
  }

  return (
    <DetailPage title={title} subtitle={subtitle}>
      {service}
      {errorMessage}
    </DetailPage>
  )
}
