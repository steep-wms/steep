import "./DefinitionListItem.scss"

export default ({ title, children }) => (
  <>
    <dt className="definition-list-title">{title}</dt>
    <dd className="definition-list-content">{children}</dd>
  </>
)
