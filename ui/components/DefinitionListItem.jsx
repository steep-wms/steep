import "./DefinitionListItem.scss"

const DefinitionListItem = ({ title, children }) => (
  <>
    <dt className="definition-list-title">{title}</dt>
    <dd className="definition-list-content">{children}</dd>
  </>
)

export default DefinitionListItem
