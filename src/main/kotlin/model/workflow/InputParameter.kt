package model.workflow

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(
    use = JsonTypeInfo.Id.DEDUCTION
)
@JsonSubTypes(
    JsonSubTypes.Type(value = GenericParameter::class),
    JsonSubTypes.Type(value = AnonymousParameter::class)
)
sealed interface InputParameter : Parameter
