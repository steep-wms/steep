import Page from "../../components/layouts/Page"
import Alert from "../../components/Alert"
import DropDown from "../../components/DropDown"
import ListItem from "../../components/ListItem"
import PluginType from "../../components/plugins/PluginType"
import { Check } from "lucide-react"
import { useRouter } from "next/router"
import useSWRImmutable from "swr/immutable"
import fetcher from "../../components/lib/json-fetcher"
import classNames from "classnames"
import styles from "./index.scss"

const PLUGIN_TYPES = ["initializer", "outputAdapter", "processChainAdapter",
  "processChainConsistencyChecker", "progressEstimator", "runtime"].sort()

const Plugins = () => {
  const { data: plugins, error } = useSWRImmutable(`${process.env.baseUrl}/plugins`, fetcher)
  const router = useRouter()
  const currentFilter = router.query.type

  function toggleFilter(type, enabled) {
    let query = { ...router.query }
    if (enabled) {
      delete query["type"]
    } else {
      query.type = type
    }
    router.push({
      pathname: router.pathname,
      query
    })
  }

  let filterDropDownElements = []
  PLUGIN_TYPES.forEach((type, i) => {
    let enabled = currentFilter === type
    filterDropDownElements.push(
      <li onClick={() => toggleFilter(type, enabled)} key={i}
          className={classNames({ enabled: enabled })}>
        {enabled && <><Check /> </>}
        <PluginType type={type} />
      </li>
    )
  })

  let pluginElements
  if (plugins !== undefined) {
    pluginElements = []
    let sortedPlugins = [...plugins]
    if (currentFilter !== undefined) {
      sortedPlugins = sortedPlugins.filter(p => p.type === currentFilter)
    }
    sortedPlugins.sort((a, b) => a.name.localeCompare(b.name))
    for (let plugin of sortedPlugins) {
      let linkHref = "/plugins/[name]"
      let linkAs = `/plugins/${plugin.name}`
      pluginElements.push(
        <ListItem key={plugin.name} linkHref={linkHref} linkAs={linkAs}
          title={plugin.name} subtitle={"Version " + plugin.version}
          labels={[<PluginType key={`${plugin.name}-type`} type={plugin.type} />]} />
      )
    }
  }

  return (
    <Page title="Plugins">
      <div className="plugins-page-title">
        <h1 className="no-margin-bottom">Plugins</h1>
        <div className="title-right">
          <DropDown title="Filter" right primary={currentFilter !== undefined}>
            <ul className={classNames("filter-list", { "has-enabled-filters": currentFilter !== undefined })}>
              {filterDropDownElements}
            </ul>
          </DropDown>
        </div>
      </div>
      {pluginElements}
      {pluginElements && pluginElements.length === 0 && <>There are no plugins.</>}
      {error && <Alert error>Could not load plugins</Alert>}
      {plugins && plugins.length === 0 && <Alert warning>There are no configured plugins</Alert>}
      <style jsx>{styles}</style>
    </Page>
  )
}

export default Plugins
