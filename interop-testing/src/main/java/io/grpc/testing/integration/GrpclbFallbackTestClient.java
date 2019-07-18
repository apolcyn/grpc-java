/*
 * Copyright 2019 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.testing.integration;

import static com.google.common.base.Preconditions.checkNotNull;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.alts.ComputeEngineChannelBuilder;
import io.grpc.testing.integration.Messages.GrpclbRouteType;
import io.grpc.testing.integration.Messages.Payload;
import io.grpc.testing.integration.Messages.SimpleRequest;
import io.grpc.testing.integration.Messages.SimpleResponse;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Test client that verifies that grpclb failover into fallback mode works under
 * different failure modes.
 */
public final class GrpclbFallbackTestClient {
  private static final Logger logger =
      Logger.getLogger(GrpclbFallbackTestClient.class.getName());

  /**
   * Entry point.
   */
  public static void main(String[] args) throws Exception {
    final GrpclbFallbackTestClient client = new GrpclbFallbackTestClient();
    client.parseArgs(args);
    client.setUp();
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      @SuppressWarnings("CatchAndPrintStackTrace")
      public void run() {
        System.out.println("Shutting down");
        try {
          client.tearDown();
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    });
    try {
      client.run();
    } finally {
      client.tearDown();
    }
    System.exit(0);
  }

  private String unrouteLBAndBackendAddrsCmd = "exit 1";
  private String blackholeLBAndBackendAddrsCmd = "exit 1";
  private String serverURI;
  private String customCredentialsType;
  private String testCase;

  private ManagedChannel channel;
  private TestServiceGrpc.TestServiceBlockingStub blockingStub;

  private void parseArgs(String[] args) {
    boolean usage = false;
    for (String arg : args) {
      if (!arg.startsWith("--")) {
        System.err.println("All arguments must start with '--': " + arg);
        usage = true;
        break;
      }
      String[] parts = arg.substring(2).split("=", 2);
      String key = parts[0];
      if ("help".equals(key)) {
        usage = true;
        break;
      }
      if (parts.length != 2) {
        System.err.println("All arguments must be of the form --arg=value");
        usage = true;
        break;
      }
      String value = parts[1];
      if ("server_uri".equals(key)) {
        serverURI = value;
      } else if ("test_case".equals(key)) {
        testCase = value;
      } else if ("unroute_lb_and_backend_addrs_cmd".equals(key)) {
        unrouteLBAndBackendAddrsCmd = value;
      } else if ("blackhole_lb_and_backend_addrs_cmd".equals(key)) {
        blackholeLBAndBackendAddrsCmd = value;
      } else if ("custom_credentials_type".equals(key)) {
        customCredentialsType = value;
      } else {
        System.err.println("Unknown argument: " + key);
        usage = true;
        break;
      }
    }
    if (usage) {
      GrpclbFallbackTestClient c = new GrpclbFallbackTestClient();
      System.out.println(
          "Usage: [ARGS...]"
          + "\n"
          + "\n  --server_uri                          Server target. Default: "
          + c.serverURI
          + "\n  --custom_credentials_type             Name of Credentials to use. "
          + "Default: " + c.customCredentialsType
          + "\n  --unroute_lb_and_backend_addrs_cmd    Shell command used to make "
          + "LB and backend addresses unroutable. Default: "
          + c.unrouteLBAndBackendAddrsCmd
          + "\n  --blackhole_lb_and_backend_addrs_cmd  Shell command used to make "
          + "LB and backend addresses black holed. Default: "
          + c.blackholeLBAndBackendAddrsCmd
          + "\n  --test_case=TEST_CASE        Test case to run. Valid options are:"
          + "\n      fast_fallback_before_startup : fallback before LB connection"
          + "\n      fast_fallback_after_startup : fallback after startup due to "
          + "LB/backend addresses becoming unroutable"
          + "\n      slow_fallback_before_startup : fallback before LB connection "
          + "due to LB/backend addresses being blackholed"
          + "\n      slow_fallback_after_startup : fallback after startup due to "
          + "LB/backend addresses becoming blackholed"
          + "\n      Default: " + c.testCase
      );
      System.exit(1);
    }
  }

  private ManagedChannel createChannel() {
    if (!customCredentialsType.equals("compute_engine_channel_creds")) {
       throw new AssertionError(
           "This test currently only supports "
           + "--custom_credentials_type=compute_engine_channel_creds.");
    }
    ComputeEngineChannelBuilder builder = ComputeEngineChannelBuilder.forTarget(serverURI);
    builder.keepAliveTime(3600, TimeUnit.SECONDS);
    builder.keepAliveTimeout(20, TimeUnit.SECONDS);
    return builder.build();
  }

  void setUp() {
    channel = createChannel();
    blockingStub = TestServiceGrpc.newBlockingStub(channel);
  }

  private void tearDown() {
    try {
      channel.shutdownNow();
      channel.awaitTermination(1, TimeUnit.SECONDS);
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  private String streamToString(InputStream inputStream) throws Exception {
    BufferedReader br = new BufferedReader(
        new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    String out = "";
    String line;
    while ((line = br.readLine()) != null) {
      out += line;
    }
    return out;
  }

  private void runShellCmd(String cmd) throws Exception {
    logger.info("Run shell command: " + cmd);
    Process process = Runtime.getRuntime().exec(new String[]{"bash", "-c", cmd});
    int exitCode = process.waitFor();
    logger.info("Shell command stdout: " + streamToString(process.getInputStream()));
    logger.info("Shell command stderr: " + streamToString(process.getErrorStream()));
    logger.info("Shell rommand exit code: " + exitCode);
    assertEquals(0, exitCode);
  }

  private enum RPCMode {
    FailFast,
    WaitForReady,
  }

  private GrpclbRouteType doRPCAndGetPath(
      TestServiceGrpc.TestServiceBlockingStub blockingStub,
      int deadlineSeconds,
      RPCMode rpcMode) {
    logger.info("doRPCAndGetPath deadlineSeconds: " + deadlineSeconds + " rpcMode: "
        + rpcMode);
    final SimpleRequest request = SimpleRequest.newBuilder()
        .setFillGrpclbRouteType(true)
        .build();
    GrpclbRouteType result = GrpclbRouteType.GRPCLB_ROUTE_TYPE_UNKNOWN;
    try {
      SimpleResponse response = null;
      if (rpcMode == RPCMode.WaitForReady) {
        response = blockingStub
          .withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS)
          .withWaitForReady()
          .unaryCall(request);
      } else {
        response = blockingStub
          .withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS)
          .unaryCall(request);
      }
      result = response.getGrpclbRouteType();
    } catch (StatusRuntimeException ex) {
      logger.warning("doRPCAndGetPath failed. Status: " + ex);
    }
    assert(result == GrpclbRouteType.GRPCLB_ROUTE_TYPE_FALLBACK ||
           result == GrpclbRouteType.GRPCLB_ROUTE_TYPE_BACKEND);
    logger.info("doRPCAndGetPath. GrpclbRouteType result: " + result);
    return result;
  }

  private void doFallbackBeforeStartupTest(
      String breakLBAndBackendConnsCmd, int deadlineSeconds) throws Exception {
    runShellCmd(breakLBAndBackendConnsCmd);
    for (int i = 0; i < 30; i++) {
      GrpclbRouteType grpclbRouteType = doRPCAndGetPath(
          blockingStub, deadlineSeconds, RPCMode.FailFast);
      assertEquals(grpclbRouteType, GrpclbRouteType.GRPCLB_ROUTE_TYPE_FALLBACK);
      Thread.sleep(1000);
    }
  }

  private void runFastFallbackBeforeStartup() throws Exception {
    doFallbackBeforeStartupTest(unrouteLBAndBackendAddrsCmd, 9);
  }

  private void runSlowFallbackBeforeStartup() throws Exception {
    doFallbackBeforeStartupTest(blackholeLBAndBackendAddrsCmd, 20);
  }

  private void doFallbackAfterStartupTest(
      String breakLBAndBackendConnsCmd) throws Exception {
    assertEquals(
        doRPCAndGetPath(blockingStub, 20, RPCMode.FailFast),
        GrpclbRouteType.GRPCLB_ROUTE_TYPE_BACKEND);
    runShellCmd(breakLBAndBackendConnsCmd);
    for (int i = 0; i < 40; i++) {
      GrpclbRouteType grpclbRouteType = doRPCAndGetPath(
          blockingStub, 1, RPCMode.WaitForReady);
      // Backends are supposed to be unreachable, the test is broken if not.
      assert(grpclbRouteType != GrpclbRouteType.GRPCLB_ROUTE_TYPE_BACKEND);
      if (grpclbRouteType == GrpclbRouteType.GRPCLB_ROUTE_TYPE_FALLBACK) {
        logger.info("Made one successful RPC to a fallback. Now expect the "
            + "same for the rest.");
        break;
      } else {
        logger.info("Retryable RPC failure on iteration: " + i);
        continue;
      }
    }
    for (int i = 0; i < 30; i++) {
      assertEquals(
          GrpclbRouteType.GRPCLB_ROUTE_TYPE_FALLBACK,
          doRPCAndGetPath(blockingStub, 20, RPCMode.FailFast));
      Thread.sleep(1000);
    }
  }

  private void runFastFallbackAfterStartup() throws Exception {
    doFallbackAfterStartupTest(unrouteLBAndBackendAddrsCmd);
  }

  private void runSlowFallbackAfterStartup() throws Exception {
    doFallbackAfterStartupTest(blackholeLBAndBackendAddrsCmd);
  }

  private void run() throws Exception {
    logger.info("Begin test case: " + testCase);
    if (testCase.equals("fast_fallback_before_startup")) {
      runFastFallbackBeforeStartup();
    } else if (testCase.equals("slow_fallback_before_startup")) {
      runSlowFallbackBeforeStartup();
    } else if (testCase.equals("fast_fallback_after_startup")) {
      runFastFallbackAfterStartup();
    } else if (testCase.equals("slow_fallback_after_startup")) {
      runSlowFallbackAfterStartup();
    } else {
      throw new RuntimeException("invalid testcase: " + testCase);
    }
    logger.info("Test case: " + testCase + " done!");
  }
}
