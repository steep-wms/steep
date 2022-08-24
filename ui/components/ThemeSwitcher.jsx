import Tooltip from "./Tooltip"
import { Moon, Sun } from "lucide-react"
import SettingsContext from "../components/lib/SettingsContext"
import { useContext } from "react"
import styles from "./ThemeSwitcher.scss"
import classNames from "classnames"

const ThemeSwitcher = () => {
  const settings = useContext(SettingsContext.State)
  const updateSettings = useContext(SettingsContext.Dispatch)

  return (<>
    <Tooltip title="Light theme">
      <span className={classNames("switch-button", { visible: settings.theme === "dark" })}
        onClick={() => updateSettings({ theme: "default" })}><Sun /></span>
    </Tooltip>
    <Tooltip title="Dark theme">
      <span className={classNames("switch-button", { visible: settings.theme !== "dark" })}
        onClick={() => updateSettings({ theme: "dark" })}><Moon /></span>
    </Tooltip>
    <style jsx>{styles}</style>
  </>)
}

export default ThemeSwitcher
