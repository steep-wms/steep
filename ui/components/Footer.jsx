import classNames from "classnames"
import styles from "./Footer.scss"
import { Book, Home, GitHub } from "react-feather"
import FraunhoferLogo from "../assets/fraunhofer.svg"

const Footer = ({ noTopMargin = false }) => (
  <footer className={classNames({ "no-top-margin": noTopMargin })}>
    <div className="footer-content">
      <div className="container">
        <div className="footer-row">
          <div className="logo">
            <a href="https://igd.fraunhofer.de"><img src={FraunhoferLogo} className="img-fluid" alt="Fraunhofer IGD logo" /></a>
          </div>
          <div className="social-icons">
            <a className="nav-item" href="https://steep-wms.github.io/" target="_blank" rel="noopener noreferrer">
              <Home />
            </a>
            <a className="nav-item" href="https://steep-wms.github.io/#documentation" target="_blank" rel="noopener noreferrer">
              <Book />
            </a>
            <a className="nav-item" href="https://github.com/steep-wms/steep" target="_blank" rel="noopener noreferrer">
              <GitHub />
            </a>
          </div>
        </div>
      </div>
    </div>
    <style jsx>{styles}</style>
  </footer>
)

export default Footer
