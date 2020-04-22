import Page from "./Page"
import Breadcrumbs from "../Breadcrumbs"
import "./DetailPage.scss"

export default ({ breadcrumbs, title, subtitle, children }) => (
  <Page title={title}>
    {title && <h1 className="detail-page-title no-margin-bottom">{title}</h1>}
    {subtitle && <p className="detail-page-subtitle">{subtitle}</p>}
    {breadcrumbs && <Breadcrumbs breadcrumbs={breadcrumbs} />}
    {(title || subtitle) && <hr className="detail-page-divider" />}
    <div className="detail-page-main">
      {children}
    </div>
  </Page>
)
