import "./VersionInfo.scss"
import Alert from "./Alert"
import DefinitionList from "./DefinitionList"
import DefinitionListItem from "./DefinitionListItem"
import useSWR from "swr"
import fetcher from "./lib/json-fetcher"

export default (props) => {
  const { data: versionInfo, error: versionInfoError } = useSWR(process.env.baseUrl, fetcher)

  if (typeof versionInfoError !== "undefined") {
    console.error(versionInfoError)
    return (
      <Alert error>Could not load version information</Alert>
    )
  } else if (typeof versionInfo === "undefined") {
    return (
      <></>
    )
  } else {
    let options = {
      day: "numeric",
      month: "long",
      year: "numeric",
      hour: "numeric",
      hour12: false,
      minute: "numeric",
      second: "numeric",
      timeZoneName: "short"
    };
    let timestamp = new Intl.DateTimeFormat("en-GB", options)
      .format(new Date(versionInfo.timestamp))
    return (
      <DefinitionList>
        <DefinitionListItem title="Version">{versionInfo.version}</DefinitionListItem>
        <DefinitionListItem title="Build">{versionInfo.build}</DefinitionListItem>
        <DefinitionListItem title="Commit">{versionInfo.commit}</DefinitionListItem>
        <DefinitionListItem title="Timestamp">{timestamp}</DefinitionListItem>
      </DefinitionList>
    )
  }
}
