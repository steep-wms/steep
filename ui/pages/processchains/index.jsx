import Link from "next/link"
import ListPage from "../../components/layouts/ListPage"
import ListItem from "../../components/ListItem"
import Tooltip from "../../components/Tooltip"
import ProcessChainContext from "../../components/processchains/ProcessChainContext"
import { useMemo } from "react"
import { useRouter } from "next/router"

const FILTERS = [{
  name: "submissionId"
}, {
  name: "status",
  title: "Failed process chains only",
  enabledValue: "ERROR"
}, {
  name: "status",
  title: "Running process chains only",
  enabledValue: "RUNNING"
}]

function ProcessChainListItem({ item: processChain }) {
  return useMemo(() => {
    let href = "/processchains/[id]"
    let as = `/processchains/${processChain.id}`

    let estimatedProgress
    if (processChain.status === "RUNNING" && processChain.estimatedProgress !== undefined &&
        processChain.estimatedProgress !== null) {
      estimatedProgress = (
        <Tooltip title="Estimated progress">
          {(processChain.estimatedProgress * 100).toFixed()}&thinsp;%
        </Tooltip>
      )
    }

    let progress = {
      status: processChain.status,
      subtitle: estimatedProgress
    }

    return <ListItem justAdded={processChain.justAdded} linkHref={href}
        linkAs={as} title={processChain.id} startTime={processChain.startTime}
        endTime={processChain.endTime} progress={progress} deleted={processChain.deleted}
        labels={processChain.requiredCapabilities} />
  }, [processChain])
}

const ProcessChains = () => {
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
      filters={FILTERS} breadcrumbs={breadcrumbs} search="processchain" />
  )
}

export default ProcessChains
