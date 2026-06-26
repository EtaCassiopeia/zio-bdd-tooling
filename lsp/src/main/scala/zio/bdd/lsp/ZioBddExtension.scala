package zio.bdd.lsp

import org.eclipse.lsp4j.jsonrpc.services.JsonRequest

import java.util.concurrent.CompletableFuture

/** Custom LSP methods specific to zio-bdd, beyond the standard protocol. */
trait ZioBddExtension:

  /** Returns the fully-formed sbt command to run a feature file or a single
   *  scenario, targeting only the suite(s) whose step definitions match the
   *  feature's steps.
   *
   *  Request: `zio-bdd/buildRunCommand`
   *  Params:  `{ "featureUri": "file:///…/calc.feature", "scenarioName": "…" | null }`
   *  Returns: shell command string, e.g. `sbt "testOnly *CalculatorSuite* -- …"`
   */
  @JsonRequest("zio-bdd/buildRunCommand")
  def buildRunCommand(params: com.google.gson.JsonObject): CompletableFuture[String]
