package zio.bdd.lsp

import zio.test.*

object MockTagSpec extends ZIOSpecDefault:

  import MockTag.Ref

  def spec = suite("MockTag")(
    test("a single name yields one ref with exact [start, end) columns") {
      // "  @mock(userService)" — '(' at 7, name starts at 8
      assertTrue(MockTag.refs("  @mock(userService)") == List(Ref("userService", 8, 19)))
    },
    test("multiple names carry their own offsets, skipping whitespace after commas") {
      // "@mock(userService, paymentGw)" — name2 begins after ", " at column 21
      assertTrue(
        MockTag.refs("@mock(userService, paymentGw)") ==
          List(Ref("userService", 6, 17), Ref("paymentGw", 19, 28))
      )
    },
    test("empty @mock() yields no refs") {
      assertTrue(MockTag.refs("@mock()").isEmpty)
    },
    test("empty comma segments are dropped, keeping the surrounding names' offsets correct") {
      // "@mock(a,,b)" — a at 6, empty middle dropped, b at 9
      assertTrue(MockTag.refs("@mock(a,,b)") == List(Ref("a", 6, 7), Ref("b", 9, 10)))
    },
    test("two separate @mock(...) tags on one line each get independent offsets") {
      assertTrue(
        MockTag.refs("@mock(a) @mock(b, c)") ==
          List(Ref("a", 6, 7), Ref("b", 15, 16), Ref("c", 18, 19))
      )
    },
    test("a non-mock line yields no refs") {
      assertTrue(MockTag.refs("@smoke @ignore").isEmpty)
    },
    test("isInsideMockCall is true within an unclosed @mock( and false once closed") {
      assertTrue(
        MockTag.isInsideMockCall("@mock("),
        MockTag.isInsideMockCall("@mock(user"),
        MockTag.isInsideMockCall("@mock(a, "),
        !MockTag.isInsideMockCall("@mock(a)"),
        !MockTag.isInsideMockCall("@"),
        !MockTag.isInsideMockCall("@smoke "),
        // a later unclosed call after an earlier closed one still counts as inside
        MockTag.isInsideMockCall("@mock(a) @mock("),
        !MockTag.isInsideMockCall("@mock(a) done")
      )
    }
  )
