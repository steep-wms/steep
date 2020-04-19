import fetcher from "./lib/json-fetcher"
import useSWR from "swr"
import "./VersionInfo.scss"
import DefinitionList from "./DefinitionList"
import DefinitionListItem from "./DefinitionListItem"

export default (props) => {
  const { data: versionInfo, error: versionInfoError } = useSWR(process.env.baseUrl, fetcher)

  if (typeof versionInfoError !== "undefined") {
    console.error(versionInfoError)
    return (
      <>Could not load version information</>
    )
  } else if (typeof versionInfo === "undefined") {
    return (
      <>Loading...</>
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
