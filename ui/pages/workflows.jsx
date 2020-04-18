import Page from "../components/layouts/Page"
import ListItem from "../components/ListItem"
import { Check } from "react-feather"

export default (props) => {
  let workflows = [];

  for (let i = 0; i < 10; ++i) {
    workflows.push(
      <ListItem key={i}>
        <div className="list-item-left">
          <h4><a href="#">ajitbf2yxrwajarqvrka</a></h4>
          Workflow finished 29 days ago and took a few seconds
        </div>
        <div className="list-item-right">
          <div className="list-item-progress-box">
            <Check className="feather" />
            <div>
              <strong>Finished</strong><br />
              2 completed
            </div>
          </div>
        </div>
      </ListItem>
    )
  }

  return (
    <Page>
      <h1>Workflows</h1>
      {workflows}
    </Page>
  )
}
