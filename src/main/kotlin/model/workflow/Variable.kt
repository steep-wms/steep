package model.workflow

import com.fasterxml.jackson.annotation.JsonIdentityInfo
import helper.UniqueID
import com.fasterxml.jackson.annotation.ObjectIdGenerators

/**
 * A variable holding a value
 * @param id the variable's unique identifier
 * @param value the variable's value (may be `null`)
 * @author Michel Kraemer
 */
@JsonIdentityInfo(
    generator = ObjectIdGenerators.PropertyGenerator::class,
    property = "id"
)
data class Variable(
    val id: String = UniqueID.next(),
    val value: Any? = null
)
