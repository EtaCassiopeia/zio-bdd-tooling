package zio.bdd.lsp.bsp

import com.google.gson.JsonParser
import zio.*

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

// Connection parameters from .bsp/<name>.json — the file a build tool writes
// to advertise its BSP server.
case class BspConnection(name: String, argv: List[String], bspVersion: String)

object BspConnectionFile:
  // Return the first valid connection file found under <workspaceRoot>/.bsp/,
  // or None when no BSP server is configured.
  def find(workspaceRoot: String): UIO[Option[BspConnection]] =
    ZIO
      .attemptBlocking {
        val bspDir = Paths.get(workspaceRoot, ".bsp")
        if !Files.isDirectory(bspDir) then None
        else
          Files
            .list(bspDir)
            .iterator()
            .asScala
            .filter(_.getFileName.toString.endsWith(".json"))
            .toList
            .sortBy(_.getFileName.toString) // deterministic: prefer alphabetically first
            .flatMap(p => parse(Files.readString(p)))
            .headOption
      }
      .orElseSucceed(None)

  private def parse(json: String): Option[BspConnection] =
    try
      val obj        = JsonParser.parseString(json).getAsJsonObject
      val name       = obj.get("name").getAsString
      val bspVersion = obj.get("bspVersion").getAsString
      val argv = obj
        .getAsJsonArray("argv")
        .asScala
        .map(_.getAsString)
        .toList
      Some(BspConnection(name, argv, bspVersion))
    catch case _: Throwable => None
