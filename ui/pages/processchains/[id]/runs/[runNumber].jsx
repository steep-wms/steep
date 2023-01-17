import { useRouter } from "next/router"
import ProcessChainDetails from "../../../../components/processchains/ProcessChainDetails"
import ProcessChainContext from "../../../../components/processchains/ProcessChainContext"

const ProcessChainRun = () => {
  const router = useRouter()
  const { id, runNumber } = router.query

  return (
    <ProcessChainContext.Provider allowAdd={false}>
      <ProcessChainDetails id={id} runNumber={+runNumber} />
    </ProcessChainContext.Provider>
  )
}

export default ProcessChainRun
