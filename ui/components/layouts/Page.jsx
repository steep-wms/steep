import Header from "../Header"
import Footer from "../Footer"
import Sidebar from "../Sidebar"
import "./Page.scss"

const Page = ({ title, children }) => (
  <div className="page">
    <Header title={title}/>
    <Sidebar />
    <main>
      <div className="container">
        {children}
      </div>
      <Footer />
    </main>
  </div>
)

export default Page
