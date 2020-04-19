import Page from "../../components/layouts/Page"
import Alert from "../../components/Alert"
import ListItem from "../../components/ListItem"
import useSWR from "swr"
import fetcher from "../../components/lib/json-fetcher"

export default () => {
  const { data: services, error: servicesError } =
      useSWR(process.env.baseUrl + "/services", fetcher)

  let serviceError
  let serviceElements = []

  if (typeof servicesError !== "undefined") {
    serviceError = <Alert error>Could not load services</Alert>
    console.error(servicesError)
  } else if (typeof services !== "undefined") {
    for (let service of services) {
      let linkHref = "/services/[id]"
      let linkAs = `/services/${service.id}`
      serviceElements.push(
        <ListItem key={service.id} linkHref={linkHref} linkAs={linkAs}
          title={service.name} subtitle={service.description}
          labels={[service.runtime]} />
      )
    }

    if (services.length === 0) {
      serviceError = "There are no configured services"
    }
  }

  return (
    <Page>
      <h1>Services</h1>
      {serviceElements}
      {serviceError}
    </Page>
  )
}
