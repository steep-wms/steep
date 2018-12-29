package model.workflow

import com.fasterxml.jackson.annotation.JsonProperty
import helper.UniqueID

data class Parameter(
    val id: String = UniqueID.next(),
    @JsonProperty("var") val variable: Variable
)
