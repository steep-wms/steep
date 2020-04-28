import Page from "../../components/layouts/Page"
import Alert from "../../components/Alert"
import ListItem from "../../components/ListItem"
import { useEffect, useState } from "react"
import fetcher from "../../components/lib/json-fetcher"

export default () => {
  const [services, setServices] = useState()
  const [error, setError] = useState()

  useEffect(() => {
    fetcher(`${process.env.baseUrl}/services`)
      .then(setServices)
      .catch(err => {
        console.log(err)
        setError(<Alert error>Could not load services</Alert>)
      })
  }, [])

  let serviceElements
  if (typeof services !== "undefined") {
    serviceElements = []
    for (let service of services) {
      let linkHref = "/services/[id]"
      let linkAs = `/services/${service.id}`
      serviceElements.push(
        <ListItem key={service.id} linkHref={linkHref} linkAs={linkAs}
          title={service.name} subtitle={service.description}
          labels={[service.runtime]} />
      )
    }
  }

  return (
    <Page title="Services">
      <h1>Services</h1>
      {serviceElements}
      {serviceElements && serviceElements.length === 0 && <>There are no services.</>}
      {error}
      {services && services.length === 0 && <Alert warning>There are no configured services</Alert>}
    </Page>
  )
}
