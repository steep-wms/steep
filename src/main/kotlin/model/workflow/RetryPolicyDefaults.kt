package model.workflow

import model.retry.RetryPolicy

/**
 * Default retry policies that should be used within a workflow unless more
 * specific retry policies are defined elsewhere
 * @author Michel Kraemer
 */
data class RetryPolicyDefaults(
    /**
     * Default retry policy for generated process chains
     * @see [model.processchain.ProcessChain.retries]
     */
    val processChains: RetryPolicy? = null
)
