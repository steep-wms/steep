import DetailPage from "../../../components/layouts/DetailPage"
import ProcessChainLog from "../../../components/ProcessChainLog"
import Link from "next/link"
import { useRouter } from "next/router"
import styles from "./[id].scss"

const ProcessChainLogs = () => {
  const router = useRouter()
  const { id } = router.query

  let breadcrumbs = [
    <Link href="/processchains" key="processchains">
      Process chains
    </Link>,
    id
  ]

  let menu = (
    <ul>
      <a
        href={`${process.env.baseUrl}/logs/processchains/${id}?forceDownload=true`}
      >
        <li>Download</li>
      </a>
    </ul>
  )

  return (
    <DetailPage
      breadcrumbs={breadcrumbs}
      title={id}
      footerNoTopMargin={true}
      menus={[
        {
          title: "Actions",
          menu
        }
      ]}
    >
      <div className="log-container">
        <ProcessChainLog id={id} />
      </div>
      <style jsx>{styles}</style>
    </DetailPage>
  )
}

export default ProcessChainLogs
