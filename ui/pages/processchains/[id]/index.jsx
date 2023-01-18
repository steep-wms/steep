import { useRouter } from "next/router"
import ProcessChainDetails from "../../../components/processchains/ProcessChainDetails"
import ProcessChainContext from "../../../components/processchains/ProcessChainContext"
import ProcessChainRunContext from "../../../components/processchains/ProcessChainRunContext"

const ProcessChain = () => {
  const router = useRouter()
  const { id } = router.query

  return (
    <ProcessChainContext.Provider allowAdd={false} pageSize={1}>
      <ProcessChainRunContext.Provider processChainId={id}>
        <ProcessChainDetails id={id} />
      </ProcessChainRunContext.Provider>
    </ProcessChainContext.Provider>
  )
}

export default ProcessChain
