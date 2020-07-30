import TimeAgo from "react-timeago"
import Tooltip from "./Tooltip"

const Ago = (props) => (
  <Tooltip title={props.title}>
    <TimeAgo {...props} title="" />
  </Tooltip>
)

export default Ago
