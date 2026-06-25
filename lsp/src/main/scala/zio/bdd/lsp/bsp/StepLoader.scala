package zio.bdd.lsp.bsp

import io.github.classgraph.ClassGraph

import scala.jdk.CollectionConverters.*

/**
 * Subprocess entry point for BSP class-loading (zio-bdd-tooling issue #2).
 *
 * The LSP server (and the IntelliJ plugin) launch this class as a subprocess on
 * the user's JVM test classpath, with the LSP fat jar appended so this class
 * can be found:
 * {{{
 *   java -cp <user-test-classpath>:<zio-bdd-lsp.jar> zio.bdd.lsp.bsp.StepLoader
 * }}}
 *
 * It scans the classpath for concrete `ZIOSteps` subclasses, instantiates each
 * one, calls `allDefinitions` (introduced in zio-bdd PR #107) via reflection,
 * and writes a JSON array to stdout.
 *
 * Errors are written to stderr; stdout always contains valid JSON (at minimum
 * `[]` on failure). Exit code 0 means the array was written; non-zero means a
 * fatal error prevented scanning entirely.
 *
 * The user's zio-bdd version must have `allDefinitions` (added in PR #107). If
 * it doesn't, a warning is printed to stderr and that class's steps are skipped
 * — the LSP falls back to the static-scan index.
 */
object StepLoader:

  def main(args: Array[String]): Unit =
    try
      val json = loadAndSerialize()
      println(json)
    catch
      case e: Throwable =>
        System.err.println(s"StepLoader: fatal error — ${e.getClass.getName}: ${e.getMessage}")
        println("[]")
        System.exit(1)

  private def loadAndSerialize(): String =
    val scan = new ClassGraph()
      .enableClassInfo()
      .scan()

    val classes =
      try scan.getSubclasses("zio.bdd.core.step.ZIOSteps").filter(!_.isAbstract).loadClasses()
      finally scan.close()

    val entries = classes.asScala.toList.flatMap { cls =>
      instantiate(cls) match
        case None =>
          System.err.println(s"StepLoader: cannot instantiate ${cls.getName} — skipping")
          Nil
        case Some(instance) =>
          callAllDefinitions(cls, instance)
    }

    buildJson(entries)

  private def instantiate(cls: Class[?]): Option[Any] =
    // Scala objects expose a static MODULE$ field; classes need a no-arg constructor.
    try Some(cls.getField("MODULE$").get(null))
    catch
      case _: NoSuchFieldException =>
        try Some(cls.getDeclaredConstructor().newInstance())
        catch case _: Throwable => None
      case _: Throwable => None

  private def callAllDefinitions(cls: Class[?], instance: Any): List[(String, String, String)] =
    val method =
      try cls.getMethod("allDefinitions")
      catch
        case _: NoSuchMethodException =>
          System.err.println(
            s"StepLoader: ${cls.getName}.allDefinitions not found " +
              "— upgrade zio-bdd to a version that includes PR #107"
          )
          return Nil

    val result =
      try method.invoke(instance)
      catch
        case e: Throwable =>
          System.err.println(
            s"StepLoader: ${cls.getName}.allDefinitions threw ${e.getClass.getName}: ${e.getMessage}"
          )
          return Nil

    // result is a scala.collection.immutable.List[StepSummary]; extract fields via reflection.
    val list =
      try result.asInstanceOf[scala.collection.immutable.List[Any]]
      catch
        case _: ClassCastException =>
          System.err.println(s"StepLoader: unexpected return type from ${cls.getName}.allDefinitions")
          return Nil

    list.flatMap { item =>
      try
        val keyword     = item.getClass.getMethod("keyword").invoke(item).toString
        val pattern     = item.getClass.getMethod("pattern").invoke(item).toString
        val displayText = item.getClass.getMethod("displayText").invoke(item).toString
        Some((keyword, pattern, displayText))
      catch
        case e: Throwable =>
          System.err.println(s"StepLoader: error reading StepSummary field: ${e.getMessage}")
          None
    }

  private def buildJson(entries: List[(String, String, String)]): String =
    if entries.isEmpty then "[]"
    else
      val items = entries.map { case (keyword, pattern, displayText) =>
        s"""{"keyword":${jsonStr(keyword)},"pattern":${jsonStr(pattern)},"displayText":${jsonStr(displayText)}}"""
      }
      s"[${items.mkString(",")}]"

  private def jsonStr(s: String): String =
    val escaped = s
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
    s""""$escaped""""
