package model.workflow

import com.fasterxml.jackson.annotation.JsonIdentityInfo
import com.fasterxml.jackson.annotation.ObjectIdGenerator
import com.fasterxml.jackson.annotation.ObjectIdGenerators
import com.fasterxml.jackson.annotation.ObjectIdResolver
import com.fasterxml.jackson.annotation.SimpleObjectIdResolver
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.BeanDescription
import com.fasterxml.jackson.databind.BeanProperty
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializationConfig
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier
import com.fasterxml.jackson.databind.ser.ContextualSerializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import helper.UniqueID

/**
 * A variable holding a value
 * @param id the variable's unique identifier
 * @param value the variable's value (may be `null`)
 * @author Michel Kraemer
 */
@JsonIdentityInfo(
    generator = ObjectIdGenerators.PropertyGenerator::class,
    property = "id",
    resolver = VariableResolver::class
)
data class Variable(
    val id: String = UniqueID.next(),
    val value: Any? = null
)

/**
 * Either resolve an object ID to a [Variable] already encountered or create
 * a new variable
 */
class VariableResolver : SimpleObjectIdResolver() {
  override fun resolveId(id: ObjectIdGenerator.IdKey): Any {
    return super.resolveId(id) ?:
        Variable(id.key.toString()).also { bindItem(id, it) }
  }

  override fun newForDeserialization(context: Any?): ObjectIdResolver =
      VariableResolver()
}

/**
 * Always serialize a variable as its ID string, unless it has a value or it
 * is part of the workflow's variables ([Workflow.vars]). In these cases, use
 * the default serializer, which will decide based on the object ID whether to
 * serialize the whole object or just a string.
 */
class VariableSerializer(private val defaultSerializer: JsonSerializer<Any>,
    private val isVars: Boolean) : StdSerializer<Variable>(Variable::class.java), ContextualSerializer {
  override fun serialize(value: Variable, gen: JsonGenerator,
      provider: SerializerProvider) {
    if (value.value != null || isVars) {
      defaultSerializer.serialize(value, gen, provider)
    } else {
      gen.writeString(value.id)
    }
  }

  /**
   * We need to override this because [defaultSerializer] (which should be of
   * type [com.fasterxml.jackson.databind.ser.BeanSerializer]) depends on a
   * context, so we must call its [createContextual] method too. If we don't do
   * this, the bean serializer will not be able to write object IDs and throw
   * a [NullPointerException] instead.
   *
   * Also, we need to check for which property we are creating a serializer. If
   * it is [Workflow.vars], we always have to serialize variables as objects.
   */
  override fun createContextual(prov: SerializerProvider, property: BeanProperty): JsonSerializer<*> {
    val isVars = property.member.declaringClass == Workflow::class.java &&
        property.name == "vars"
    @Suppress("UNCHECKED_CAST")
    return VariableSerializer((defaultSerializer as ContextualSerializer)
        .createContextual(prov, property) as JsonSerializer<Any>, isVars)
  }
}

/**
 * A serializer modifier that replaces the default [com.fasterxml.jackson.databind.ser.BeanSerializer]
 * with a [VariableSerializer] if the bean to be serialized is a [Variable]. We
 * can't just use the [com.fasterxml.jackson.databind.JsonSerializable] annotation
 * to specify a serializer for [Variable], because this will replace the
 * [JsonIdentityInfo] logic and variable references will not be correctly written.
 */
class VariableSerializerModifier : BeanSerializerModifier() {
  override fun modifySerializer(config: SerializationConfig,
      beanDesc: BeanDescription, serializer: JsonSerializer<*>): JsonSerializer<*> {
    if (beanDesc.beanClass == Variable::class.java) {
      @Suppress("UNCHECKED_CAST")
      return VariableSerializer(serializer as JsonSerializer<Any>, false)
    }
    return serializer
  }
}
