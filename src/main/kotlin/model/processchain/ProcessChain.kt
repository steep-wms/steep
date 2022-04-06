package model.processchain

import com.fasterxml.jackson.annotation.JsonInclude
import helper.UniqueID

/**
 * A process chain describes a set of actions (i.e. [Executable]s) that should
 * be executed by [agent.Agent]s
 * @param id the process chain's unique identifier
 * @param executables the list of [Executable]s to actually be executed
 * @param requiredCapabilities a set of capabilities this process chain needs
 * the host system to have to be able to run
 * @param priority a priority used during scheduling. Process chains with
 * higher priorities will be scheduled before those with lower priorities.
 * @author Michel Kraemer
 */
data class ProcessChain(
    val id: String = UniqueID.next(),
    val executables: List<Executable> = emptyList(),
    val requiredCapabilities: Set<String> = emptySet(),
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    val priority: Int = 0
)
