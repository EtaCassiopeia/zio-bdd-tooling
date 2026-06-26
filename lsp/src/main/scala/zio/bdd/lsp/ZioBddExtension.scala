package zio.bdd.lsp

import org.eclipse.lsp4j.jsonrpc.services.JsonRequest

import java.util.concurrent.CompletableFuture

/** Custom LSP methods specific to zio-bdd, beyond the standard protocol. */
trait ZioBddExtension:

  /**
   * Returns the fully-formed sbt command to run a feature file or a single
   * scenario, targeting only the suite(s) whose step definitions match the
   * feature's steps.
   *
   * Request: `zio-bdd/buildRunCommand` Params: `{ "featureUri":
   * "file:///…/calc.feature", "scenarioName": "…" | null }` Returns: shell
   * command string, e.g. `sbt "testOnly *CalculatorSuite* -- …"`
   */
  @JsonRequest("zio-bdd/buildRunCommand")
  def buildRunCommand(params: com.google.gson.JsonObject): CompletableFuture[String]

  /**
   * Returns a JSON array of `{ suiteName, scalaFile, featurePaths }` objects
   * mapping each indexed suite to the feature files whose steps it covers. Used
   * by the Scenario Explorer sidebar to group features by owning suite.
   *
   * Request: `zio-bdd/suiteFeatureMap` Params: `{}` (unused) Returns: JSON
   * string — `[{ "suiteName": "…", "scalaFile": "…", "featurePaths": ["…"] }]`
   */
  @JsonRequest("zio-bdd/suiteFeatureMap")
  def suiteFeatureMap(params: com.google.gson.JsonObject): CompletableFuture[String]
