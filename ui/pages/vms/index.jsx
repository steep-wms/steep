import ListPage from "../../components/layouts/ListPage"

export default () => {
  // parse query params but do not use "next/router" because router.query
  // is empty on initial render
  let pageOffset
  let pageSize
  if (typeof window !== "undefined") {
    let params = new URLSearchParams(window.location.search)
    pageOffset = params.get("offset") || undefined
    if (typeof pageOffset !== "undefined") {
      pageOffset = Math.max(0, parseInt(pageOffset))
    }
    pageSize = params.get("size") || 10
    if (typeof pageSize !== "undefined") {
      pageSize = Math.max(0, parseInt(pageSize))
    }
  }

  return (
    <ListPage title="VMs">
      <h1>VMs</h1>
      {/*<WorkflowContext.Provider pageSize={pageSize} onWorkflowChanged={onWorkflowChanged}>
        <WorkflowList pageSize={pageSize} pageOffset={pageOffset} />
      </WorkflowContext.Provider>*/}
    </ListPage>
  )
}
