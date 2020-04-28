import TimeAgo from "react-timeago"
import Tooltip from "./Tooltip"

export default (props) => (
  <Tooltip title={props.title}>
    <TimeAgo {...props} title="" />
  </Tooltip>
)
