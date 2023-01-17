import { useRouter } from "next/router"
import ProcessChainDetails from "../../../components/processchains/ProcessChainDetails"
import ProcessChainContext from "../../../components/processchains/ProcessChainContext"

const ProcessChain = () => {
  const router = useRouter()
  const { id } = router.query

  return (
    <ProcessChainContext.Provider allowAdd={false}>
      <ProcessChainDetails id={id} />
    </ProcessChainContext.Provider>
  )
}

export default ProcessChain
