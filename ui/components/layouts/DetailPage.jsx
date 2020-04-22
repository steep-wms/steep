import Page from "./Page"
import "./DetailPage.scss"

function Breadcrumbs({ breadcrumbs }) {
  let first = breadcrumbs.slice(0, breadcrumbs.length - 1)
  let elements = [
    ...(first.map((b, i) => <span key={i}>{b} &raquo; </span>)),
    <span key={breadcrumbs.length - 1}>{breadcrumbs[breadcrumbs.length - 1]}</span>
  ]
  return <div className="detail-page-breadcrumbs">{elements}</div>
}

export default ({ breadcrumbs, title, subtitle, children }) => (
  <Page>
    {title && <h1 className="detail-page-title">{title}</h1>}
    {subtitle && <p className="detail-page-subtitle">{subtitle}</p>}
    {breadcrumbs && <Breadcrumbs breadcrumbs={breadcrumbs} />}
    {(title || subtitle) && <hr className="detail-page-divider" />}
    <div className="detail-page-main">
      {children}
    </div>
  </Page>
)
