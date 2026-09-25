package com.opentooling.loggate.storage;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * A real S3-compatible server for the storage tests: Versity S3 Gateway,
 * serving buckets from a directory inside the container.
 *
 * <p>Versity rather than MinIO because MinIO no longer publishes images a
 * fresh machine can pull. Pinned, like every image the build depends on, so a
 * new release cannot change what the tests assert against.
 */
final class S3TestServer extends GenericContainer<S3TestServer> {

  static final String IMAGE = "versity/versitygw:v1.8.0";
  static final String ACCESS_KEY = "loggate-test";
  static final String SECRET_KEY = "loggate-test-secret";
  private static final int PORT = 7070;

  S3TestServer() {
    super(DockerImageName.parse(IMAGE));
    withEnv("ROOT_ACCESS_KEY", ACCESS_KEY);
    withEnv("ROOT_SECRET_KEY", SECRET_KEY);
    // The bucket directory has to exist before the gateway serves it.
    withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh", "-c"));
    // One argument for sh -c: the single-string overload splits on spaces.
    withCommand(new String[] {"mkdir -p /tmp/s3 && exec versitygw --port :" + PORT + " posix /tmp/s3"});
    withExposedPorts(PORT);
    waitingFor(Wait.forListeningPort());
    withStartupAttempts(3);
  }

  /** The endpoint to point an S3 client at. */
  String endpoint() {
    return "http://" + getHost() + ":" + getMappedPort(PORT);
  }
}
