import Link from "next/link"
import Label from "../Label"
import styles from "./Examples.scss"
import dayjs from "dayjs"

const Examples = () => {
  return (
    <>
      <div className="examples">
        <div className="example">
          <div className="example-title">Terms:</div>
          <div>
            <Link href="/search?q=filename+highmemory">
              <Label>filename highmemory</Label>
            </Link>
          </div>
          <div>
            <Link href='/search?q="exact+match"'>
              <Label>&quot;exact match&quot;</Label>
            </Link>
          </div>
        </div>

        <div className="example">
          <div className="example-title">Types:</div>
          <div>
            <Link href="/search?q=filename+is:workflow">
              <Label>filename is:workflow</Label>
            </Link>
          </div>
          <div>
            <Link href="/search?q=error:127+is:processchain">
              <Label>error:127 is:processchain</Label>
            </Link>
          </div>
        </div>

        <div className="example">
          <div className="example-title">Attributes:</div>
          <div>
            <Link href="/search?q=highmemory+in:requiredcapabilities">
              <Label>highmemory in:requiredcapabilities</Label>
            </Link>
          </div>
          <div>
            <Link href="/search?q=rcs:highmemory">
              <Label>rcs:highmemory</Label>
            </Link>
          </div>
          <div>
            <Link href="/search?q=filename+status:error">
              <Label>filename status:error</Label>
            </Link>
          </div>
        </div>

        <div className="example">
          <div className="example-title">Date/Time:</div>
          <div>
            <Link href={`/search?q=${dayjs(Date.now()).format("YYYY-MM-DD")}`}>
              <Label>{dayjs(Date.now()).format("YYYY-MM-DD")}</Label>
            </Link>
          </div>
          <div>
            <Link
              href={`/search?q=<${dayjs(Date.now()).format(
                "YYYY-MM-DDTHH:mm"
              )}`}
            >
              <Label>&lt;{dayjs(Date.now()).format("YYYY-MM-DDTHH:mm")}</Label>
            </Link>
          </div>
          <div>
            <Link
              href={`/search?q=start:>=${dayjs(new Date() - 3600000).format(
                "YYYY-MM-DDTHH:mm:ss"
              )}`}
            >
              <Label>
                start:&gt;=
                {dayjs(new Date() - 3600000).format("YYYY-MM-DDTHH:mm:ss")}
              </Label>
            </Link>
          </div>
          <div>
            <Link
              href={`/search?q=${dayjs(new Date() - 86400000).format(
                "YYYY-MM-DD"
              )}..${dayjs(Date.now()).format("YYYY-MM-DD")}`}
            >
              <Label>
                {dayjs(new Date() - 86400000).format("YYYY-MM-DD")}..
                {dayjs(Date.now()).format("YYYY-MM-DD")}
              </Label>
            </Link>
          </div>
        </div>

        <div className="example combined">
          <div className="example-title">Combined:</div>
          <div>
            <Link href="/search?q=filename+in:source+is:workflow+rcs:highmemory">
              <Label>filename in:source is:workflow rcs:highmemory</Label>
            </Link>
          </div>
          <div>
            <Link href="/search?q=exit+code+127+in:error+is:processchain">
              <Label>exit code 127 in:error is:processchain</Label>
            </Link>
          </div>
        </div>
      </div>
      <style jsx>{styles}</style>
    </>
  )
}

export default Examples
