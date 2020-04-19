import Page from "./Page"
import "./DetailPage.scss"

export default ({ title, subtitle, children }) => (
  <Page>
    <h1 className="detail-page-title">{title}</h1>
    <p className="detail-page-subtitle">{subtitle}</p>
    <hr className="detail-page-divider" />
    {children}
  </Page>
)
