import Link from "next/link"
import ListPage from "../../components/layouts/ListPage"
import ListItem from "../../components/ListItem"
import ProcessChainContext from "../../components/processchains/ProcessChainContext"
import { useMemo } from "react"
import { useRouter } from "next/router"

const FILTERS = [{
  name: "submissionId"
}, {
  name: "status",
  title: "Failed process chains only",
  enabledValue: "ERROR"
}]

function ProcessChainListItem({ item: processChain }) {
  return useMemo(() => {
    let href = "/processchains/[id]"
    let as = `/processchains/${processChain.id}`

    let progress = {
      status: processChain.status
    }

    return <ListItem justAdded={processChain.justAdded} linkHref={href}
        linkAs={as} title={processChain.id} startTime={processChain.startTime}
        endTime={processChain.endTime} progress={progress}
        labels={processChain.requiredCapabilities} />
  }, [processChain])
}

export default () => {
  const router = useRouter()

  let breadcrumbs
  if (router.query.submissionId !== undefined) {
    let sid = router.query.submissionId
    breadcrumbs = [
      <Link href="/workflows" key="workflows"><a>Workflows</a></Link>,
      <Link href="/workflows/[id]" as={`/workflows/${sid}`} key={sid}>
        <a>{sid}</a>
      </Link>,
      "Process chains"
    ]
  }

  return (
    <ListPage title="Process chains" Context={ProcessChainContext}
      ListItem={ProcessChainListItem} subjects="process chains" path="processchains"
      filters={FILTERS} breadcrumbs={breadcrumbs} />
  )
}
