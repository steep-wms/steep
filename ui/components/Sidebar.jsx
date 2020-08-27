import classNames from "classnames"
import Link from "next/link"
import styles from "./Sidebar.scss"
import { Grid, Link as LinkIcon, Pocket, Send, Server } from "react-feather"
import { useState } from "react"

function NavItem({ href, icon, text }) {
  // On small screens, the sidebar shows icons only. "nav-item-text" will be
  // shown when the cursor hovers over the nav item (like a tooltip). We handle
  // hover state in JavaScript and not in pure CSS because we want the tooltip
  // to disappear when we click a nav item.
  const [hover, setHover] = useState()

  return (
    <div className="nav-item">
      <Link href={href}>
        <a className={classNames("nav-item", "nav-item-link", { hover })}
            onMouseEnter={() => setHover(true)}
            onMouseLeave={() => setHover(false)}>
          {icon} <span className="nav-item-text">{text}</span>
        </a>
      </Link>
      <style jsx>{styles}</style>
    </div>
  )
}

const Sidebar = () => {
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
          <NavItem href="/workflows/" icon={<Send />} text="Workflows" />
          <NavItem href="/processchains/" icon={<LinkIcon />} text="Process Chains" />
          <NavItem href="/agents/" icon={<Pocket />} text="Agents" />
          <NavItem href="/vms/" icon={<Server />} text="VMs" />
          <NavItem href="/services/" icon={<Grid />} text="Services" />
        </nav>
      </div>
      <style jsx>{styles}</style>
    </aside>
  )
}

export default Sidebar
