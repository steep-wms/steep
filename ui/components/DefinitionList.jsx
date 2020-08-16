import styles from "./DefinitionList.scss"

const DefinitionList = ({ children }) => (
  <dl className="definition-list">
    {children}
    <style jsx>{styles}</style>
  </dl>
)

export default DefinitionList
