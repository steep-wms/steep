package model.workflow

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import helper.UniqueID

data class OutputParameter(
    override val id: String = UniqueID.next(),
    @JsonProperty("var") override val variable: Variable,
    val prefix: String? = null,
    @JsonInclude(JsonInclude.Include.NON_DEFAULT) val store: Boolean = false
) : Parameter
