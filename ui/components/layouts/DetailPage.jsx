import Page from "./Page"
import "./DetailPage.scss"

export default ({ title, subtitle, children }) => (
  <Page>
    {title && <h1 className="detail-page-title">{title}</h1>}
    {subtitle && <p className="detail-page-subtitle">{subtitle}</p>}
    {(title || subtitle) && <hr className="detail-page-divider" />}
    {children}
  </Page>
)
