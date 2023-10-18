package model.workflow

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonInclude
import helper.UniqueID

/**
 * A workflow action that includes a macro
 * @param macro the ID of the macro to include
 * @param inputs a list of macro input parameters
 * @param outputs a list of include output parameters
 * @author Michel Kraemer
 */
data class IncludeAction(
    override val id: String = UniqueID.next(),
    val macro: String,
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    val inputs: List<InputParameter> = emptyList(),
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    val outputs: List<IncludeOutputParameter> = emptyList(),
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    @JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    override val dependsOn: List<String> = emptyList()
) : Action
