import Header from "../Header"
import Footer from "../Footer"
import "./Page.scss"

export default (props) => (
  <main>
    <Header title={props.title}/>
    <div className="container page">
      {props.children}
    </div>
    <Footer />
  </main>
);
