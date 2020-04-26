import Page from "./Page"
import "./ListPage.scss"

export default (props) => (
  <Page {...props}>
    <div className="list-page">
      {props.children}
    </div>
  </Page>
)
