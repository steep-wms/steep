import classNames from "classnames"
import styles from "./Footer.scss"
import { Book, Home, Github } from "lucide-react"
import FraunhoferLogo from "../assets/fraunhofer.svg"
import FraunhoferLogoWhite from "../assets/fraunhofer-white.svg"
import SettingsContext from "./lib/SettingsContext"
import { useContext } from "react"

const Footer = ({ noTopMargin = false }) => {
  const settings = useContext(SettingsContext.State)

  let logo = settings.theme === "dark" ? FraunhoferLogoWhite : FraunhoferLogo

  return <footer className={classNames({ "no-top-margin": noTopMargin })}>
    <div className="footer-content">
      <div className="container">
        <div className="footer-row">
          <div className="logo">
            <a href="https://igd.fraunhofer.de"><img src={logo} className="img-fluid" alt="Fraunhofer IGD logo" /></a>
          </div>
          <div className="social-icons">
            <a className="nav-item" href="https://steep-wms.github.io/" target="_blank" rel="noopener noreferrer">
              <Home />
            </a>
            <a className="nav-item" href="https://steep-wms.github.io/#documentation" target="_blank" rel="noopener noreferrer">
              <Book />
            </a>
            <a className="nav-item" href="https://github.com/steep-wms/steep" target="_blank" rel="noopener noreferrer">
              <Github />
            </a>
          </div>
        </div>
      </div>
    </div>
    <style jsx>{styles}</style>
  </footer>
}

export default Footer
