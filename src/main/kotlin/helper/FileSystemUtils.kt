package helper

import io.vertx.core.file.FileSystem
import io.vertx.kotlin.coroutines.await

/**
 * Utility functions for the Vert.x [FileSystem]
 * @author Michel Kraemer
 */
object FileSystemUtils {
  /**
   * Recursively get all files from the given path. If the path is a file, the
   * method will return a list with only one entry: the file itself.
   * @param dirOrFile a directory or a file
   * @param fs the Vert.x file system
   * @param filter an optional file filter
   * @return the list of files found
   */
  suspend fun readRecursive(dirOrFile: String, fs: FileSystem,
      filter: ((String) -> Boolean)? = null): List<String> {
    val r = mutableListOf<String>()
    val q = ArrayDeque<String>()
    q.add(dirOrFile)
    while (!q.isEmpty()) {
      val f = q.removeFirst()
      if (fs.props(f).await().isDirectory) {
        q.addAll(fs.readDir(f).await())
      } else {
        if (filter == null || filter(f)) {
          r.add(f)
        }
      }
    }
    return r
  }
}
