package cloud

import db.VMRegistry
import model.cloud.PoolAgentParams
import model.setup.Setup
import org.slf4j.LoggerFactory
import kotlin.math.min

/**
 * Selects setups matching given required capabilities and according to
 * information found in the [vmRegistry] and the limits configured in the
 * [poolAgentParams].
 * @author Michel Kraemer
 */
class SetupSelector(private val vmRegistry: VMRegistry,
    private val poolAgentParams: List<PoolAgentParams>) {
  companion object {
    private val log = LoggerFactory.getLogger(SetupSelector::class.java)
  }

  /**
   * Iterate through [nVMsPerSetup] and [nCreatedPerSetup] and count how many
   * of the VMs provide the given [capabilities]
   */
  private fun countVMsWithCapabilities(nVMsPerSetup: Map<String, Long>,
      nCreatedPerSetup: MutableMap<String, Long>, capabilities: List<String>,
      setups: List<Setup>): Long {
    // also consider how many VMs we are about to create
    val merged = (nCreatedPerSetup.entries + nVMsPerSetup.entries)
        .groupBy { it.key }.mapValues { e -> e.value.sumOf { v -> v.value } }

    val setupsById = setups.associateBy { it.id }
    return merged.map { (setupId, n) ->
      val setup = setupsById[setupId]
      if (setup?.providedCapabilities?.containsAll(capabilities) == true) {
        n
      } else {
        0
      }
    }.sum()
  }

  /**
   * Get the number of non-terminated VMs per setup from the registry
   */
  private suspend fun countNonTerminatedVMsPerSetup(): Map<String, Long> {
    return vmRegistry.findNonTerminatedVMs().groupBy { it.setup.id }
        .mapValues { it.value.size.toLong() }
  }

  /**
   * Select [n] setups with [requiredCapabilities] from the given list of
   * [setups]. Use information about VMs currently running or currently being
   * started from the [VMRegistry]. Repeat a certain setup up to [n] times in
   * the resulting collection if the given information and configured limits
   * allow for it. Return an empty list if [setups] does not contain any
   * setup that would satisfy the given [requiredCapabilities].
   *
   * The resulting collection can subsequently be used to create VMs. For
   * example, if you call this method with `n = 3` and it returns a list with
   * three elements `[setup1, setup1, setup2]`, you can create three VMs with
   * these setups.
   */
  suspend fun select(n: Long, requiredCapabilities: Collection<String>,
      setups: List<Setup>): List<Setup> {
    // select candidate setups that satisfy the given required capabilities
    val matchingSetups = setups.filter { it.providedCapabilities.containsAll(requiredCapabilities) }
    if (matchingSetups.isEmpty()) {
      return emptyList()
    }

    // reduce n by starting VMs
    val starting = matchingSetups.map { vmRegistry.countStartingVMsBySetup(it.id) }
    var remaining = n
    remaining -= starting.sum()

    val result = mutableListOf<Setup>()
    val nCreatedPerSetup = mutableMapOf<String, Long>()
    var nVMsPerSetup: Map<String, Long>? = null

    outer@for ((i, setup) in matchingSetups.withIndex()) {
      if (remaining <= 0) {
        break
      }

      val existingVMs = vmRegistry.countNonTerminatedVMsBySetup(setup.id)
      if (existingVMs >= setup.maxVMs.toLong()) {
        // we already created more than enough virtual machines with this setup
        log.trace("There already are $existingVMs virtual machines with " +
            "setup `${setup.id}'. The maximum number is ${setup.maxVMs}.")
        continue
      }
      var toCreate = remaining.coerceAtMost(setup.maxVMs.toLong() - existingVMs)

      // check if we already have enough VMs that provide similar capabilities
      for (params in poolAgentParams) {
        if (params.max != null && setup.providedCapabilities.containsAll(params.capabilities)) {
          // Creating a new VM with [setup] would add an agent with the given
          // provided capabilities. Check if this would exceed the maximum
          // number of agents...

          // count how many VMs there are already
          if (nVMsPerSetup == null) {
            nVMsPerSetup = countNonTerminatedVMsPerSetup()
          }

          // now count how many of them provide the requested capabilities
          val m = countVMsWithCapabilities(nVMsPerSetup, nCreatedPerSetup,
              params.capabilities, setups)
          if (m >= params.max) {
            // there already are enough VMs with these capabilities
            log.trace("There already are $n VMs with capabilities " +
                "${params.capabilities}. The maximum number is ${params.max}.")
            continue@outer
          }

          toCreate = toCreate.coerceAtMost(params.max - m)
        }
      }

      // limit the number of VMs to create concurrently
      toCreate = toCreate.coerceAtMost(setup.maxCreateConcurrent.toLong() - starting[i])

      // Make sure toCreate is not negative! This can happen if there are more
      // VMs starting than configured by maxCreateConcurrent (for whatever
      // reason). A negative number leads to a wrong count in nCreatedPerSetup,
      // which in turn may make countVMsWithCapabilities return incorrect
      // results, which in turn may lead to too many VMs being created
      toCreate = toCreate.coerceAtLeast(0)

      // add setup to result list and repeat it as many times as possible
      for (j in 0 until toCreate) {
        result.add(setup)
      }
      nCreatedPerSetup[setup.id] = (nCreatedPerSetup[setup.id] ?: 0) + toCreate
      remaining -= toCreate
    }

    return result
  }

  /**
   * Return a list of VMs to create according to the minimum numbers configured
   * in the given [setups] and [poolAgentParams]. If the [removeExisting] flag
   * is set, the method does not return entries for existing VMs with matching
   * setups (i.e. it only returns entries necessary to create new VMs and to
   * reach the specified minima).
   */
  suspend fun selectMinimum(setups: List<Setup>, removeExisting: Boolean = true): List<Setup> {
    val result = mutableListOf<Setup>()

    // determine minimum number of VMs
    val pap = poolAgentParams.toMutableList()
    for (setup in setups) {
      val maximum = setup.maxVMs
      var minimum = min(setup.minVMs, maximum)

      val papToAdd = mutableListOf<PoolAgentParams>()
      val papi = pap.iterator()
      while (papi.hasNext()) {
        val p = papi.next()
        if (setup.providedCapabilities.containsAll(p.capabilities)) {
          // we found parameters our setup satisfies
          if (p.min > minimum) {
            minimum = min(p.min, maximum)
          }
          papi.remove()

          // Pool agent param needs more VMs than we can provide with this
          // setup. Save param for next iteration.
          if (p.min - minimum > 0) {
            papToAdd.add(p.copy(min = p.min - minimum))
          }
        }
      }
      pap.addAll(papToAdd)

      for (j in 0 until minimum) {
        result.add(setup)
      }
    }

    // limit number of VMs to maxima defined in pool agent params
    for (p in poolAgentParams) {
      if (p.max != null) {
        // count number of VMs that match the param's required capabilities
        // and delete those that are too many
        var count = 0
        val i = result.iterator()
        while (i.hasNext()) {
          val setup = i.next()
          if (setup.providedCapabilities.containsAll(p.capabilities)) {
            if (count == p.max) {
              i.remove()
            } else {
              count++
            }
          }
        }
      }
    }

    if (removeExisting) {
      // reduce the number of VMs to create by the number of existing ones
      val nVMsPerSetup = countNonTerminatedVMsPerSetup()
      val nToCreatePerSetup = mutableMapOf<String, Int>()
      val i = result.iterator()
      while (i.hasNext()) {
        val setup = i.next()
        val nExisting = nVMsPerSetup[setup.id]?.toInt() ?: 0
        val nToCreate = nToCreatePerSetup[setup.id] ?: 0
        if (nToCreate < nExisting) {
          i.remove()
        }
        nToCreatePerSetup[setup.id] = nToCreate + 1
      }
    }

    return result
  }
}
