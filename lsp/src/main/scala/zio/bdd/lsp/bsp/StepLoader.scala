package zio.bdd.lsp.bsp

import io.github.classgraph.{ClassGraph, ClassInfo}

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
 *
 * Extraction is deliberately backend-agnostic and side-effect-free: patterns
 * are registered in a suite's constructor body, so loading never needs a live
 * mock backend. A `MockSteps`-based suite (mixin `{ self: ZIOSteps[R &
 * MockControl, S] => }`) only needs `MockControl` at run time, inside step
 * bodies the loader never executes. Any suite that fails to load, link,
 * initialise, or instantiate — e.g. one referencing `zio-bdd-rift-embedded`
 * (Panama FFM) on a JDK where FFM is unavailable or `--enable-preview` is
 * absent — is isolated and skipped so it can never abort extraction for the
 * rest of the project.
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
      try
        scan
          .getSubclasses("zio.bdd.core.step.ZIOSteps")
          .filter(!_.isAbstract)
          .asScala
          .toList
          .flatMap(loadClassSafely)
      finally scan.close()

    buildJson(stepsFromClasses(classes))

  /**
   * Load one candidate class in isolation. ClassGraph's batch `loadClasses`
   * aborts the whole load on the first class that cannot be linked; loading
   * each class on its own instead keeps a single unloadable suite (e.g. one
   * that references embedded-Rift FFM classes on a JDK without FFM) from wiping
   * out step intelligence for every other suite. We use `loadClass()`
   * (`ignoreExceptions = false`), which throws `IllegalArgumentException`
   * wrapping the real `ClassNotFoundException`/`LinkageError`, and catch it
   * here so the skipped suite is always logged — the `loadClass(true)` overload
   * would instead swallow those internally and return null with no signal,
   * precisely for the embedded-FFM case we most want visibility into.
   */
  private[bsp] def loadClassSafely(ci: ClassInfo): Option[Class[?]] =
    try Some(ci.loadClass())
    catch
      case e: Throwable =>
        val cause = unwrap(e)
        System.err.println(
          s"StepLoader: cannot load ${ci.getName} — skipping (${cause.getClass.getName}: ${cause.getMessage})"
        )
        None

  private[bsp] def stepsFromClasses(classes: List[Class[?]]): List[(String, String, String)] =
    classes.flatMap(stepsFor)

  /**
   * Extract step definitions from one already-loaded suite class. Instantiation
   * and the `allDefinitions` call are each guarded so a single misbehaving
   * suite (side-effecting constructor, missing or throwing method) is skipped
   * rather than aborting extraction for every other suite on the classpath.
   */
  private[bsp] def stepsFor(cls: Class[?]): List[(String, String, String)] =
    instantiate(cls) match
      case Left(e) =>
        System.err.println(
          s"StepLoader: cannot instantiate ${cls.getName} — skipping (${e.getClass.getName}: ${e.getMessage})"
        )
        Nil
      case Right(instance) =>
        callAllDefinitions(cls, instance)

  private[bsp] def instantiate(cls: Class[?]): Either[Throwable, Any] =
    // Scala objects expose a static MODULE$ field; classes need a no-arg constructor.
    // Both paths can trigger class initialisation, which for a suite referencing an
    // unavailable backend may raise an Error (ExceptionInInitializerError,
    // NoClassDefFoundError) rather than an Exception — so catch Throwable, keeping
    // extraction from ever requiring a live backend. The failure is returned rather
    // than discarded so the caller can log which suite failed and why.
    try Right(cls.getField("MODULE$").get(null))
    catch
      case _: NoSuchFieldException =>
        try Right(cls.getDeclaredConstructor().newInstance())
        catch case e: Throwable => Left(unwrap(e))
      case e: Throwable => Left(unwrap(e))

  // Reflective construction wraps the true cause in ExceptionInInitializerError /
  // InvocationTargetException; report the underlying failure when there is one.
  private def unwrap(e: Throwable): Throwable = Option(e.getCause).getOrElse(e)

  private[bsp] def callAllDefinitions(cls: Class[?], instance: Any): List[(String, String, String)] =
    val method =
      try cls.getMethod("allDefinitions")
      catch
        case _: NoSuchMethodException =>
          System.err.println(
            s"StepLoader: ${cls.getName}.allDefinitions not found " +
              "— upgrade zio-bdd to a version that includes PR #107"
          )
          return Nil
        // getMethod resolves the method's signature types; an unavailable backend
        // type in the signature raises a LinkageError here. Skip this suite rather
        // than let it escape and abort extraction for every other suite.
        case e: Throwable =>
          System.err.println(
            s"StepLoader: cannot resolve ${cls.getName}.allDefinitions — skipping " +
              s"(${e.getClass.getName}: ${e.getMessage})"
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
