import DetailPage from "../../../components/layouts/DetailPage"
import { useRouter } from "next/router"
import { useEffect, useState } from "react"
import Alert from "../../../components/Alert"
import DefinitionList from "../../../components/DefinitionList"
import DefinitionListItem from "../../../components/DefinitionListItem"
import Label from "../../../components/Label"
import Link from "next/link"
import styles from "./[name].scss"
import { formatDurationMilliseconds } from "../../../components/lib/date-time-utils"
import fetcher from "../../../components/lib/json-fetcher"

const Plugin = () => {
  const router = useRouter()
  const { type, name } = router.query

  const [data, setData] = useState()
  const [error, setError] = useState()

  useEffect(() => {
    if (type && name) {
      fetcher(`${process.env.baseUrl}/plugins/${type}/${name}`)
        .then(setData)
        .catch(err => {
          console.log(err)
          setError(<Alert error>Could not load plugin</Alert>)
        })
    }
  }, [type, name])

  let title
  let subtitle
  let plugin
  let none = <span className="none"></span>

  if (data !== undefined) {
    title = data.name
    plugin = (<>
      <div className="plugin-details">
        <DefinitionList>
          <div className="plugin-details-dl">
            <div className="plugin-details-left">
              <DefinitionListItem title="Type">{data.type}</DefinitionListItem>
              <DefinitionListItem title="Script File">{data.scriptFile}</DefinitionListItem>
              {data.supportedRuntime && <DefinitionListItem title="Supported Runtime">{data.supportedRuntime}</DefinitionListItem> }
              {data.supportedDataType && <DefinitionListItem title="Supported Data Type">{data.supportedDataType}</DefinitionListItem> }
            </div>
            <div className="plugin-details-right">
              <DefinitionListItem title="Version">{data.version}</DefinitionListItem>
            </div>
          </div>
        </DefinitionList>
      </div>

      {data.dependsOn && data.dependsOn.length > 0 && (<>
        <h2>Depends On</h2>
        <div className="plugin-list">
          {data.dependsOn.map(r => {
            let linkHref = "/plugins/[type]/[r]"
            let linkAs = `/plugins/${type}/${r}`
            return (
              <Link key={linkAs} href={linkHref} as={linkAs}><a className="list-item-title-link">{r}</a></Link>
            )
          })}
        </div>
      </>)}

      {data.supportedServiceIds && data.supportedServiceIds.length > 0 && (<>
        <h2>Supported Service IDs</h2>
        <div className="plugin-list">
          {data.supportedServiceIds.map(r => {
            let linkHref = "/services/[r]"
            let linkAs = `/services/${r}`
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
      {error}
      <style jsx>{styles}</style>
    </DetailPage>
  )
}

export default Plugin
