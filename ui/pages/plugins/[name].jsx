import DetailPage from "../../components/layouts/DetailPage"
import { useRouter } from "next/router"
import Alert from "../../components/Alert"
import DefinitionList from "../../components/DefinitionList"
import DefinitionListItem from "../../components/DefinitionListItem"
import PluginType from "../../components/plugins/PluginType"
import Label from "../../components/Label"
import Link from "next/link"
import styles from "./[name].scss"
import useSWRImmutable from "swr/immutable"
import fetcher from "../../components/lib/json-fetcher"

const Plugin = () => {
  const router = useRouter()
  const { name } = router.query
  const { data, error } = useSWRImmutable(() => name && `${process.env.baseUrl}/plugins/${name}`, fetcher)

  let title
  let subtitle
  let plugin

  if (data !== undefined) {
    title = data.name
    plugin = (<>
      <div className="plugin-details">
        <DefinitionList>
          <div className="plugin-details-dl">
            <div className="plugin-details-left">
              <DefinitionListItem title="Type"><Label><PluginType type={data.type} /></Label></DefinitionListItem>
              <DefinitionListItem title="Script file">{data.scriptFile}</DefinitionListItem>
              {data.supportedRuntime && <DefinitionListItem title="Supported runtime">{data.supportedRuntime}</DefinitionListItem>}
              {data.supportedDataType && <DefinitionListItem title="Supported data type">{data.supportedDataType}</DefinitionListItem>}
            </div>
            <div className="plugin-details-right">
              <DefinitionListItem title="Version">{data.version}</DefinitionListItem>
            </div>
          </div>
        </DefinitionList>
      </div>

      {data.supportedServiceIds && data.supportedServiceIds.length > 0 && (<>
        <h2>IDs of supported services</h2>
        <div className="plugin-list">
          {data.supportedServiceIds.map(r => {
            let linkHref = "/services/[id]"
            let linkAs = `/services/${r}`
            return (
              <Link key={linkAs} href={linkHref} as={linkAs}><a className="list-item-title-link">{r}</a></Link>
            )
          })}
        </div>
      </>)}

      {data.dependsOn && data.dependsOn.length > 0 && (<>
        <h2>Depends on</h2>
        <div className="plugin-list">
          {data.dependsOn.map(r => {
            let linkHref = "/plugins/[name]"
            let linkAs = `/plugins/${r}`
            return (
              <Link key={linkAs} href={linkHref} as={linkAs}><a className="list-item-title-link">{r}</a></Link>
            )
          })}
        </div>
      </>)}

      <style jsx>{styles}</style>
    </>)
  }

  return (
    <DetailPage title={title} subtitle={subtitle}>
      {plugin}
      {error && <Alert error>Could not load plugin</Alert>}
      <style jsx>{styles}</style>
    </DetailPage>
  )
}

export default Plugin
