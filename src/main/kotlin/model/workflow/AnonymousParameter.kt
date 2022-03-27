package model.workflow

import com.fasterxml.jackson.annotation.JsonIgnore
import helper.UniqueID

data class AnonymousParameter(
    override val id: String = UniqueID.next(),
    val value: Any?
) : InputParameter {
  @JsonIgnore
  override val variable: Variable = Variable(
      id = "${id}_${UniqueID.next()}",
      value = value
  )
}
