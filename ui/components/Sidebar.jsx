import Link from "next/link"
import "./Sidebar.scss"
import { Grid, Link as LinkIcon, Pocket, Send, Server } from "react-feather"

export default () => {
  function switchToClassicUI() {
    document.cookie = "beta=;path=/;expires=Thu, 01 Jan 1970 00:00:00 GMT"
    let params = new URLSearchParams(window.location.search)
    params.delete("beta")
    window.location.search = params.toString()
  }

  return (
    <aside>
      <div className="sidebar">
        <Link href="/">
          <a className="sidebar-logo">
            <img src={require("../assets/steep-logo.svg")} width="160" className="steep-logo" />
            <img src={require("../assets/steep-icon.svg")} width="1000" className="steep-icon" />
          </a>
        </Link>
        <nav>
          <div className="nav-item">
            <Link href="/workflows/">
              <a className="nav-item">
                <Send className="feather" /> <span className="nav-item-text">Workflows</span>
              </a>
            </Link>
          </div>
          <div className="nav-item">
            <Link href="/processchains/">
              <a className="nav-item">
                <LinkIcon className="feather" /> <span className="nav-item-text">Process Chains</span>
              </a>
            </Link>
          </div>
          <div className="nav-item">
            <Link href="/agents/">
              <a className="nav-item">
                <Pocket className="feather" /> <span className="nav-item-text">Agents</span>
              </a>
            </Link>
          </div>
          <div className="nav-item">
            <Link href="/vms/">
              <a className="nav-item">
                <Server className="feather" /> <span className="nav-item-text">VMs</span>
              </a>
            </Link>
          </div>
          <div className="nav-item">
            <Link href="/services/">
              <a className="nav-item">
                <Grid className="feather" /> <span className="nav-item-text">Services</span>
              </a>
            </Link>
          </div>
        </nav>
        <div className="sidebar-rest">
          <div className="sidebar-switch-to-classic" onClick={switchToClassicUI}>
            Back to classic UI ...
          </div>
        </div>
      </div>
    </aside>
  )
}
