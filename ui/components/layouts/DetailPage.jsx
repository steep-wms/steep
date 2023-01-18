import Page from "./Page"
import Breadcrumbs from "../Breadcrumbs"
import styles from "./DetailPage.scss"
import DropDown from "../DropDown"
import classNames from "classnames"

const DetailPage = ({
  breadcrumbs,
  title,
  subtitle,
  menus = [],
  deleted = false,
  footerNoTopMargin = false,
  children
}) => (
  <Page title={title} footerNoTopMargin={footerNoTopMargin}>
    {title && (
      <div className={classNames("detail-page-title", { deleted })}>
        <h1 className="no-margin-bottom">{title}</h1>
        <div className="drop-down-container">
          {menus.map(menu => (
            <DropDown key={menu.title} title={menu.title} right={true}>
              {menu.menu}
            </DropDown>
          ))}
        </div>
      </div>
    )}
    {subtitle && <p className="detail-page-subtitle">{subtitle}</p>}
    {breadcrumbs && <Breadcrumbs breadcrumbs={breadcrumbs} />}
    {(title || subtitle) && <hr className="detail-page-divider" />}
    <div className={classNames("detail-page-main", { deleted })}>
      {children}
    </div>
    <style jsx>{styles}</style>
  </Page>
)

export default DetailPage
