import Header from "../Header"
import Footer from "../Footer"
import Sidebar from "../Sidebar"
import styles from "./Page.scss"

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
    <style jsx>{styles}</style>
  </div>
)

export default Page
