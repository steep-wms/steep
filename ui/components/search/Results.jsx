import Alert from "../Alert"
import ResultsRow from "./ResultsRow"
import styles from "./Results.scss"

const Results = ({ results = {} }) => {
  let resres = results.results || []
  let rows = resres.map(r => <ResultsRow result={r} key={r.id} />)
  let info
  if (rows.length === 0) {
    info = <Alert info>Your search did not yield any results.</Alert>
  }
  return (<>
    <div className="results-container">
      {rows}
      {info}
    </div>
    <style jsx>{styles}</style>
  </>)
}

export default Results
