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
      <a>Process chains</a>
    </Link>,
    id
  ]

  return (
    <DetailPage breadcrumbs={breadcrumbs} title={id} footerNoTopMargin={true}>
      <div className="log-container">
        <ProcessChainLog id={id} />
      </div>
      <style jsx>{styles}</style>
    </DetailPage>
  )
}

export default ProcessChainLogs
