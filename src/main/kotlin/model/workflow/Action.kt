package model.workflow

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonSubTypes.Type
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * A workflow action
 * @author Michel Kraemer
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type")
@JsonSubTypes(
    Type(value = ExecuteAction::class, name = "execute"),
    Type(value = ForEachAction::class, name = "for")
)
sealed interface Action {
    /**
     * The action's unique identifier
     */
    val id: String

    /**
     * A list of identifiers of actions this action needs to finish first
     * before it is ready to be executed
     */
    val dependsOn: List<String>
}
