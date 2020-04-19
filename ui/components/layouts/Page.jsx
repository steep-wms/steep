import Header from "../Header"
import Footer from "../Footer"
import Sidebar from "../Sidebar"
import "./Page.scss"

export default (props) => (
  <div className="page">
    <Header title={props.title}/>
    <Sidebar />
    <main>
      <div className="container">
        {props.children}
      </div>
      <Footer />
    </main>
  </div>
)
