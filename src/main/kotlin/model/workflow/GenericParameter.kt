package model.workflow

import com.fasterxml.jackson.annotation.JsonProperty
import helper.UniqueID

data class GenericParameter(
    override val id: String = UniqueID.next(),
    @JsonProperty("var") override val variable: Variable
) : InputParameter
