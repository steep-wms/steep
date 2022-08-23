import Page from "../../components/layouts/Page"
import Alert from "../../components/Alert"
import ListItem from "../../components/ListItem"
import { useEffect, useState } from "react"
import fetcher from "../../components/lib/json-fetcher"

const Plugins = () => {
  const [plugins, setPlugins] = useState()
  const [error, setError] = useState()

  useEffect(() => {
    fetcher(`${process.env.baseUrl}/plugins`)
      .then(setPlugins)
      .catch(err => {
        console.log(err)
        setError(<Alert error>Could not load plugins</Alert>)
      })
  }, [])

  let pluginElements
  if (plugins !== undefined) {
    pluginElements = []
    let sortedPlugins = [...plugins]
    sortedPlugins.sort((a, b) => a.type.localeCompare(b.type) || a.name.localeCompare(b.name))
    let lastType = ""
    for (let plugin of sortedPlugins) {
      let linkHref = "/plugins/[type]/[name]"
      let linkAs = `/plugins/${plugin.type}/${plugin.name}`
      if (lastType !== plugin.type) {
        lastType = plugin.type
        pluginElements.push(<h2>{plugin.type}</h2>)
      }
      pluginElements.push(
        <ListItem key={plugin.type + "-" + plugin.name} linkHref={linkHref} linkAs={linkAs}
          title={plugin.name} subtitle={"Version " + plugin.version} />
      )
    }
  }

  return (
    <Page title="Plugins">
      <h1>Plugins</h1>
      {pluginElements}
      {pluginElements && pluginElements.length === 0 && <>There are no plugins.</>}
      {error}
      {plugins && plugins.length === 0 && <Alert warning>There are no configured plugins</Alert>}
    </Page>
  )
}

export default Plugins
