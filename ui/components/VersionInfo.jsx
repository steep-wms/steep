import fetch from "unfetch"
import useSWR from "swr"
import "./VersionInfo.scss"

const fetcher = url => fetch(url).then(r => r.json())

export default (props) => {
  const { data: versionInfo } = useSWR("http://localhost:8080", fetcher)

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
