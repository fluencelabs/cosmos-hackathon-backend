package hackhack.docker

sealed trait DockerError extends Throwable

case class DockerCommandError(cmd: String, cause: Throwable)
    extends Exception(s"Failed to execute shell command: `$cmd`", cause)
    with DockerError

case class DockerException(message: String, cause: Throwable)
    extends Exception(message, cause)
    with DockerError

case class DockerContainerStopped(startedAt: Long) extends DockerError
