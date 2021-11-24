package model.timeout

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import helper.StringDurationToMillisecondsConverter

/**
 * Defines rules for how long an [model.processchain.Executable] can run before
 * its execution is automatically aborted
 * @author Michel Kraemer
 */
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
data class TimeoutPolicy @JsonCreator constructor(
  /**
   * The maximum number of milliseconds the execution may take before it is
   * aborted.
   */
  @JsonDeserialize(converter = StringDurationToMillisecondsConverter::class)
  val timeout: Long,

  /**
   * `true` if an execution that is aborted due to a timeout should lead
   * to an error (i.e. if the process chain's status should be set to
   * [db.SubmissionRegistry.ProcessChainStatus.ERROR]). `false` if it should
   * just be cancelled ([db.SubmissionRegistry.ProcessChainStatus.CANCELLED]).
   * By default, the execution will be cancelled.
   */
  val errorOnTimeout: Boolean = false
) {
  /**
   * Convenience constructor that allows a string to be deserialized to a
   * timeout policy
   */
  @JsonCreator
  constructor(strTimeout: String):
      this(timeout = StringDurationToMillisecondsConverter().convert(strTimeout)) {
  }

  /**
   * Convenience constructor that allows an integer to be deserialized to a
   * timeout policy
   */
  @JsonCreator
  constructor(intTimeout: Int): this(timeout = intTimeout.toLong()) {
  }

  /**
   * Convenience constructor that allows a long integer to be deserialized to a
   * timeout policy
   */
  @JsonCreator
  constructor(longTimeout: Long): this(timeout = longTimeout) {
  }
}
