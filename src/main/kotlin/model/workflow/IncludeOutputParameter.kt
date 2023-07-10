package model.workflow

import com.fasterxml.jackson.annotation.JsonProperty
import helper.UniqueID

data class IncludeOutputParameter(
    override val id: String = UniqueID.next(),
    @JsonProperty("var") override val variable: Variable,
) : Parameter
