package zio.bdd.lsp

/**
 * Feature-file view of the `@mock(name, ...)` tag, used by completion and
 * diagnostics to locate catalog-name references and their text ranges.
 *
 * Mirrors zio-bdd's `MockTag` (`^mock\(([^)]*)\)$`, comma-separated names,
 * trimmed, empties dropped) but works on a raw editor line rather than an
 * already-split tag, so it also reports each name's column span.
 */
object MockTag:

  /**
   * One catalog-name reference on a line: the name and its [start, end)
   * columns.
   */
  final case class Ref(name: String, start: Int, end: Int)

  private val call = """@mock\(([^)]*)\)""".r

  /** Every catalog-name reference across all `@mock(...)` tags on the line. */
  def refs(line: String): List[Ref] =
    call.findAllMatchIn(line).flatMap(m => namesWithOffsets(m.group(1), m.start(1))).toList

  /**
   * True when `prefix` (a line up to the caret) ends inside an unclosed
   * `@mock(` — i.e. catalog-name completion should fire.
   */
  def isInsideMockCall(prefix: String): Boolean =
    val idx = prefix.lastIndexOf("@mock(")
    idx >= 0 && !prefix.substring(idx + "@mock(".length).contains(")")

  // Split a comma-separated group into trimmed names, each carrying its absolute
  // column span (base = the group's offset in the line). Empty segments — e.g.
  // `@mock()` or a dangling `a, ` — contribute no name, matching MockTag.parse.
  private def namesWithOffsets(group: String, base: Int): List[Ref] =
    val out    = List.newBuilder[Ref]
    var offset = 0
    group.split(",", -1).foreach { part =>
      val trimmed = part.trim
      if trimmed.nonEmpty then
        val start = base + offset + part.indexOf(trimmed)
        out += Ref(trimmed, start, start + trimmed.length)
      offset += part.length + 1 // advance past the segment and its comma
    }
    out.result()
