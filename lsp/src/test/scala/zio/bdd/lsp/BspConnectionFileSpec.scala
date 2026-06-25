package zio.bdd.lsp

import zio.*
import zio.bdd.lsp.bsp.{BspConnection, BspConnectionFile}
import zio.test.*

import java.nio.file.{Files, Paths}

object BspConnectionFileSpec extends ZIOSpecDefault:

  def spec = suite("BspConnectionFile")(
    test("returns None when no .bsp directory exists") {
      val tmp = Files.createTempDirectory("bsp-test-empty")
      for result <- BspConnectionFile.find(tmp.toString)
      yield assertTrue(result.isEmpty)
    },
    test("returns None when .bsp directory contains no .json files") {
      val tmp = Files.createTempDirectory("bsp-test-nojson")
      Files.createDirectory(tmp.resolve(".bsp"))
      Files.writeString(tmp.resolve(".bsp/ignored.txt"), "not json")
      for result <- BspConnectionFile.find(tmp.toString)
      yield assertTrue(result.isEmpty)
    },
    test("parses a valid sbt BSP connection file") {
      val tmp = Files.createTempDirectory("bsp-test-valid")
      Files.createDirectory(tmp.resolve(".bsp"))
      Files.writeString(
        tmp.resolve(".bsp/sbt.json"),
        """{
          |  "name": "sbt",
          |  "argv": ["sbt", "--client", "bspConfig"],
          |  "version": "1.10.7",
          |  "bspVersion": "2.1.0",
          |  "languages": ["scala", "java"]
          |}""".stripMargin
      )
      for result <- BspConnectionFile.find(tmp.toString)
      yield assertTrue(
        result.isDefined,
        result.get.name == "sbt",
        result.get.argv == List("sbt", "--client", "bspConfig"),
        result.get.bspVersion == "2.1.0"
      )
    },
    test("ignores malformed JSON without throwing") {
      val tmp = Files.createTempDirectory("bsp-test-malformed")
      Files.createDirectory(tmp.resolve(".bsp"))
      Files.writeString(tmp.resolve(".bsp/bad.json"), "not { valid json }")
      for result <- BspConnectionFile.find(tmp.toString)
      yield assertTrue(result.isEmpty)
    },
    test("prefers alphabetically first file when multiple .json files exist") {
      val tmp = Files.createTempDirectory("bsp-test-multi")
      Files.createDirectory(tmp.resolve(".bsp"))
      Files.writeString(
        tmp.resolve(".bsp/bloop.json"),
        """{"name":"bloop","argv":["bloop","bsp"],"version":"1.0","bspVersion":"2.0","languages":["scala"]}"""
      )
      Files.writeString(
        tmp.resolve(".bsp/sbt.json"),
        """{"name":"sbt","argv":["sbt","bsp"],"version":"1.10","bspVersion":"2.1","languages":["scala"]}"""
      )
      for result <- BspConnectionFile.find(tmp.toString)
      yield assertTrue(result.exists(_.name == "bloop")) // "bloop" < "sbt" alphabetically
    }
  )
