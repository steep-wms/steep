import styles from "./DefinitionListItem.scss"

const DefinitionListItem = ({ title, children }) => (
  <>
    <dt className="definition-list-title">{title}</dt>
    <dd className="definition-list-content">{children}</dd>
    <style jsx>{styles}</style>
  </>
)

export default DefinitionListItem
