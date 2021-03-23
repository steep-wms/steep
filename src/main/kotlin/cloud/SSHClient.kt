package cloud

import helper.DefaultOutputCollector
import helper.Shell.execute
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.awaitResult
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import java.io.IOException
import kotlin.coroutines.CoroutineContext

/**
 * Connects to a remote machine via SSH
 * @param ip the remote machine's IP address
 * @param username the username used for authentication
 * @param identityFile the path to a private key file used for authentication
 * @param vertx the Vert.x instance
 */
class SSHClient(private val ip: String, private val username: String,
    private val identityFile: String, private val vertx: Vertx) : CoroutineScope {
  override val coroutineContext: CoroutineContext = vertx.dispatcher()

  /**
   * Try to connect to the remote machine. Throw an [IOException] if the
   * connection attempt was not successful within the given [timeoutSeconds].
   * Otherwise return normally.
   */
  suspend fun tryConnect(timeoutSeconds: Int) {
    blocking {
      execute(listOf("ssh",
          "-i", identityFile,
          "-o", "ConnectTimeout=$timeoutSeconds",
          "-o", "LogLevel=ERROR",
          "-o", "StrictHostKeyChecking=no",
          "-o", "UserKnownHostsFile=/dev/null",
          "$username@$ip",
          "echo ok"), DefaultOutputCollector(1), false)
    }
  }

  /**
   * Upload a [src] file to the given [dest] on the remote machine.
   */
  suspend fun uploadFile(src: String, dest: String, recursive: Boolean = false) {
    uploadFiles(listOf(src), dest, recursive)
  }

  /**
   * Upload multiple [sources] to the given [dest] on the remote machine.
   */
  suspend fun uploadFiles(sources: List<String>, dest: String, recursive: Boolean = false) {
    blocking {
      uploadFilesBlocking(sources, dest, recursive)
    }
  }

  /**
   * Blocking version of [uploadFiles]
   */
  fun uploadFilesBlocking(sources: List<String>, dest: String, recursive: Boolean = false) {
    val args = mutableListOf("scp")
    if (recursive) {
      args.add("-r")
    }
    args.addAll(listOf(
        "-i", identityFile,
        "-o", "LogLevel=ERROR",
        "-o", "StrictHostKeyChecking=no",
        "-o", "UserKnownHostsFile=/dev/null"
    ))
    args.addAll(sources)
    args.add("$username@$ip:$dest")
    execute(args)
  }

  /**
   * Execute a given [command] on the remote machine. Throw a
   * [helper.Shell.ExecutionException] if the command was not successful.
   */
  suspend fun execute(command: String) {
    blocking {
      executeBlocking(command)
    }
  }

  /**
   * Blocking version of [execute]
   */
  fun executeBlocking(command: String) {
    execute(listOf("ssh",
        "-i", identityFile,
        "-o", "LogLevel=ERROR",
        "-o", "StrictHostKeyChecking=no",
        "-o", "UserKnownHostsFile=/dev/null",
        "$username@$ip", command))
  }

  private suspend fun blocking(block: () -> Unit) {
    // execute commands in a separate worker executor with a very long timeout
    val executor = vertx.createSharedWorkerExecutor(SSHClient::class.simpleName,
        30, Long.MAX_VALUE)
    try {
      awaitResult<Unit> { handler ->
        // call `executeBlocking` with `ordered = false` to enable
        // parallel execution of blocking code
        executor.executeBlocking<Unit>({ f ->
          block()
          f.complete()
        }, false, { ar ->
          handler.handle(ar)
        })
      }
    } finally {
      executor.close()
    }
  }
}
