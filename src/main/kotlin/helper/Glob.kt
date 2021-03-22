package helper

import org.apache.commons.io.FilenameUtils
import org.apache.commons.lang3.SystemUtils
import org.apache.tools.ant.Project
import org.apache.tools.ant.types.FileSet
import java.io.File
import java.nio.file.Paths

/**
 * Resolve files from the given list of glob patterns. For example, /tmp/&#42;
 * retrieves all files in the /tmp directory. /tmp/&#42;&#42;/&#42; recursively
 * retrieves all files from /tmp and all its subdirectory. Uses Ant's [FileSet]
 * internally. Returns a map of root paths and files found within these root
 * paths.
 */
fun glob(patterns: Collection<String>): Map<String, List<String>> {
  return glob(*patterns.toTypedArray())
}

/**
 * See [glob]
 */
fun glob(vararg patterns: String): Map<String, List<String>> {
  /**
   * Check if the given string contains a glob character ('*', '{', '?', or '[')
   */
  fun hasGlobCharacter(s: String): Boolean {
    var i = 0
    while (i < s.length) {
      val c = s[i]
      if (c == '\\') {
        ++i
      } else if (c == '*' || c == '{' || c == '?' || c == '[') {
        return true
      }
      ++i
    }
    return false
  }

  val files = mutableListOf<Pair<String, String>>()
  for (p in patterns) {
    // convert Windows backslashes to slashes
    val pattern = if (SystemUtils.IS_OS_WINDOWS) {
      FilenameUtils.separatorsToUnix(p)
    } else {
      p
    }

    // collect paths and glob patterns
    val roots = mutableListOf<String>()
    val globs = mutableListOf<String>()
    val parts = pattern.split("/")
    var rootParsed = false
    for (part in parts) {
      if (!rootParsed) {
        if (hasGlobCharacter(part)) {
          globs.add(part)
          rootParsed = true
        } else {
          roots.add(part)
        }
      } else {
        globs.add(part)
      }
    }

    if (globs.isEmpty()) {
      // string does not contain a glob pattern at all
      files.add(parts.dropLast(1).joinToString("/") to parts.last())
    } else {
      // string contains a glob pattern
      if (roots.isEmpty()) {
        // there are no paths in the string. start from the current
        // working directory
        roots.add(".")
      }

      // add all files matching the pattern
      val root = roots.joinToString("/")
      val glob = globs.joinToString("/")
      val project = Project()
      val fs = FileSet()
      fs.dir = File(root)
      fs.setIncludes(glob)
      val ds = fs.getDirectoryScanner(project)
      ds.includedFiles.mapTo(files) { root to it }
    }
  }

  return files.groupBy({ it.first }, { it.second })
}
