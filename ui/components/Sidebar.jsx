import Link from "next/link"
import "./Sidebar.scss"
import { Grid, Link as LinkIcon, Pocket, Send, Server } from "react-feather"

export default () => {
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
      </div>
    </aside>
  )
}
