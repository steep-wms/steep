import classNames from "classnames"
import "./NavBar.scss"
import Link from "next/link"
import { useState } from "react"

export default () => {
  const [collapse, setCollapse] = useState(false);

  return (
    <nav className={classNames("navbar", { collapse })} id="main-navbar">
      <div className="container">
        <div className="head">
          <Link href="/">
            <a className="navbar-brand">
              <img src={require("../assets/steep-logo.svg")} width="160" />
            </a>
          </Link>
          <div className="button" onClick={() => setCollapse(!collapse)}>
            <span></span>
            <span></span>
            <span></span>
          </div>
        </div>
        <Link href="/workflows/"><a className="nav-item">Workflows</a></Link>
        <Link href="/processchains/"><a className="nav-item">Process Chains</a></Link>
        <Link href="/agents/"><a className="nav-item">Agents</a></Link>
        <Link href="/vms/"><a className="nav-item">VMs</a></Link>
        <Link href="/services/"><a className="nav-item">Services</a></Link>
      </div>
    </nav>
  )
}
