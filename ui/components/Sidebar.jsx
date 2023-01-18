import classNames from "classnames"
import Link from "next/link"
import resolvedStyles from "./Sidebar.scss?type=resolve"
import styles from "./Sidebar.scss"
import {
  LayoutGrid,
  Link as LinkIcon,
  Pocket,
  Puzzle,
  Search,
  Send,
  Server
} from "lucide-react"
import { useContext, useState } from "react"
import SteepLogo from "../assets/steep-logo.svg"
import SteepLogoWhite from "../assets/steep-logo-white.svg"
import SteepIcon from "../assets/steep-icon.svg"
import SteepIconLight from "../assets/steep-icon-light.svg"
import ThemeSwitcher from "./ThemeSwitcher"
import SettingsContext from "./lib/SettingsContext"

function NavItem({ href, icon, text }) {
  // On small screens, the sidebar shows icons only. "nav-item-text" will be
  // shown when the cursor hovers over the nav item (like a tooltip). We handle
  // hover state in JavaScript and not in pure CSS because we want the tooltip
  // to disappear when we click a nav item.
  const [hover, setHover] = useState()

  return (
    <div className="nav-item">
      <Link
        href={href}
        className={classNames(
          resolvedStyles.className,
          "nav-item",
          "nav-item-link",
          { hover }
        )}
        onMouseEnter={() => setHover(true)}
        onMouseLeave={() => setHover(false)}
      >
        {icon} <span className="nav-item-text">{text}</span>
      </Link>
      {resolvedStyles.styles}
      <style jsx>{styles}</style>
    </div>
  )
}

const Sidebar = () => {
  const settings = useContext(SettingsContext.State)

  let logo = settings.theme === "dark" ? SteepLogoWhite : SteepLogo
  let icon = settings.theme === "dark" ? SteepIconLight : SteepIcon

  return (
    <aside>
      <div className="sidebar">
        <div className="sidebar-main">
          <Link
            href="/"
            className={classNames(resolvedStyles.className, "sidebar-logo")}
          >
            <img
              src={logo}
              width="160"
              className="steep-logo"
              alt="Steep logo"
            />
            <img
              src={icon}
              width="1000"
              className="steep-icon"
              alt="Steep logo (icon only)"
            />
          </Link>
          <nav>
            <NavItem href="/workflows/" icon={<Send />} text="Workflows" />
            <NavItem
              href="/processchains/"
              icon={<LinkIcon />}
              text="Process Chains"
            />
            <NavItem href="/agents/" icon={<Pocket />} text="Agents" />
            <NavItem href="/vms/" icon={<Server />} text="VMs" />
            <NavItem href="/services/" icon={<LayoutGrid />} text="Services" />
            <NavItem href="/plugins/" icon={<Puzzle />} text="Plugins" />
            <NavItem href="/search/" icon={<Search />} text="Search" />
          </nav>
        </div>
        <div className="sidebar-footer">
          <ThemeSwitcher />
        </div>
      </div>
      {resolvedStyles.styles}
      <style jsx>{styles}</style>
    </aside>
  )
}

export default Sidebar
