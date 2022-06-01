import Link from "next/link"
import Label from "../Label"
import styles from "./Examples.scss"
import dayjs from "dayjs"

const Examples = () => {
  return (<>
    <div className="examples">
      <div className="example">
        <div className="example-title">Terms:</div>
        <div><Link href="/search?q=filename+highmemory">
          <a><Label>filename highmemory</Label></a>
        </Link></div>
        <div><Link href="/search?q=&quot;exact+match&quot;">
          <a><Label>&quot;exact match&quot;</Label></a>
        </Link></div>
      </div>

      <div className="example">
        <div className="example-title">Types:</div>
        <div><Link href="/search?q=filename+is:workflow">
          <a><Label>filename is:workflow</Label></a>
        </Link></div>
        <div><Link href="/search?q=error:127+is:processchain">
          <a><Label>error:127 is:processchain</Label></a>
        </Link></div>
      </div>

      <div className="example">
        <div className="example-title">Attributes:</div>
        <div><Link href="/search?q=highmemory+in:requiredcapabilities">
          <a><Label>highmemory in:requiredcapabilities</Label></a>
        </Link></div>
        <div><Link href="/search?q=rcs:highmemory">
          <a><Label>rcs:highmemory</Label></a>
        </Link></div>
        <div><Link href="/search?q=filename+status:error">
          <a><Label>filename status:error</Label></a>
        </Link></div>
      </div>

      <div className="example">
        <div className="example-title">Date/Time:</div>
        <div><Link href={`/search?q=${dayjs(Date.now()).format("YYYY-MM-DD")}`}>
          <a><Label>{dayjs(Date.now()).format("YYYY-MM-DD")}</Label></a>
        </Link></div>
        <div><Link href={`/search?q=<${dayjs(Date.now()).format("YYYY-MM-DDTHH:mm")}`}>
          <a><Label>&lt;{dayjs(Date.now()).format("YYYY-MM-DDTHH:mm")}</Label></a>
        </Link></div>
        <div><Link href={`/search?q=start:>=${dayjs(new Date() - 3600000).format("YYYY-MM-DDTHH:mm:ss")}`}>
          <a><Label>start:&gt;={dayjs(new Date() - 3600000).format("YYYY-MM-DDTHH:mm:ss")}</Label></a>
        </Link></div>
        <div><Link href={`/search?q=${dayjs(new Date() - 86400000).format("YYYY-MM-DD")}..${dayjs(Date.now()).format("YYYY-MM-DD")}`}>
          <a><Label>{dayjs(new Date() - 86400000).format("YYYY-MM-DD")}..{dayjs(Date.now()).format("YYYY-MM-DD")}</Label></a>
        </Link></div>
      </div>

      <div className="example combined">
        <div className="example-title">Combined:</div>
        <div><Link href="/search?q=filename+in:source+is:workflow+rcs:highmemory">
          <a><Label>filename in:source is:workflow rcs:highmemory</Label></a>
        </Link></div>
        <div><Link href="/search?q=exit+code+127+in:error+is:processchain">
          <a><Label>exit code 127 in:error is:processchain</Label></a>
        </Link></div>
      </div>
    </div>
    <style jsx>{styles}</style>
  </>)
}

export default Examples
