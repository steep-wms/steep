import "./Breadcrumbs.scss"

export default ({ breadcrumbs }) => {
  let first = breadcrumbs.slice(0, breadcrumbs.length - 1)
  let elements = [
    ...(first.map((b, i) => <span key={i}>{b} &raquo; </span>)),
    <span key={breadcrumbs.length - 1}>{breadcrumbs[breadcrumbs.length - 1]}</span>
  ]
  return <div className="breadcrumbs">{elements}</div>
}
