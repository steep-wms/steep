import Alert from "./Alert"
import DefinitionList from "./DefinitionList"
import DefinitionListItem from "./DefinitionListItem"
import useSWRImmutable from "swr/immutable"
import fetcher from "./lib/json-fetcher"

const VersionInfo = () => {
  const { data, error } = useSWRImmutable(process.env.baseUrl, fetcher)

  if (error !== undefined) {
    return <Alert error>Could not load version information</Alert>
  } else if (data === undefined) {
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
    }
    let timestamp = new Intl.DateTimeFormat("en-GB", options)
      .format(new Date(data.timestamp))
    return (
      <DefinitionList>
        <DefinitionListItem title="Version">{data.version}</DefinitionListItem>
        <DefinitionListItem title="Build">{data.build}</DefinitionListItem>
        <DefinitionListItem title="Commit">{data.commit}</DefinitionListItem>
        <DefinitionListItem title="Timestamp">{timestamp}</DefinitionListItem>
      </DefinitionList>
    )
  }
}

export default VersionInfo
