package model.metadata

import helper.YamlUtils
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for [RuntimeArgument]
 * @author Michel Kraemer
 */
class RuntimeArgumentTest {
  /**
   * Test if a value can be a simple string
   */
  @Test
  fun stringValue() {
    val conf = """
      id: volumeMounts
      name: Data directory
      description: Mount data directory
      value: /data
    """.trimIndent()

    val expected = RuntimeArgument(
        id = "volumeMounts",
        name = "Data directory",
        description = "Mount data directory",
        value = """/data"""
    )

    val ra: RuntimeArgument = YamlUtils.readValue(conf)
    assertThat(ra).isEqualTo(expected)
  }

  /**
   * Test if a value can be an object and if it is correctly deserialized to
   * a string
   */
  @Test
  fun objectValue() {
    val conf = """
      id: volumeMounts
      name: Data directory
      description: Mount data directory
      value:
        - name: data-dir
          mountPath: /data
    """.trimIndent()

    val expected = RuntimeArgument(
        id = "volumeMounts",
        name = "Data directory",
        description = "Mount data directory",
        value = """[{"name":"data-dir","mountPath":"/data"}]"""
    )

    val ra: RuntimeArgument = YamlUtils.readValue(conf)
    assertThat(ra).isEqualTo(expected)
  }
}
