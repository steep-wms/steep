import fetcher from "./lib/json-fetcher"
import useSWR from "swr"
import "./VersionInfo.scss"

export default (props) => {
  const { data: versionInfo } = useSWR(process.env.baseUrl, fetcher)

  if (versionInfo) {
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
      <div className="version-info">
        <dl>
          <dt>Version:</dt>
          <dd>{versionInfo.version}</dd>
          <dt>Build:</dt>
          <dd>{versionInfo.build}</dd>
          <dt>Commit:</dt>
          <dd>{versionInfo.commit}</dd>
          <dt>Timestamp:</dt>
          <dd>{timestamp}</dd>
        </dl>
      </div>
    )
  } else {
    return (
      <>Loading...</>
    )
  }
}
