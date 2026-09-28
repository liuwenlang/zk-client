package wiki.twom.plugin.zk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonFormatTest {

    @Test
    fun `formats flat object`() {
        assertEquals(
            """{
  "a": 1,
  "b": "x"
}""",
            JsonFormat.tryFormat("""{"a":1,"b":"x"}"""),
        )
    }

    @Test
    fun `keeps spacing inside strings`() {
        assertEquals(
            """{
  "msg": "hello  world"
}""",
            JsonFormat.tryFormat("""{"msg":"hello  world"}"""),
        )
    }

    @Test
    fun `escapes do not terminate strings`() {
        val src = """{"k":"a\"b","n":2}"""
        assertEquals(
            """{
  "k": "a\"b",
  "n": 2
}""",
            JsonFormat.tryFormat(src),
        )
    }

    @Test
    fun `nested arrays and objects`() {
        assertEquals(
            """{
  "list": [
    1,
    [
      2
    ]
  ],
  "obj": {
    "deep": true
  }
}""",
            JsonFormat.tryFormat("""{"list":[1,[2]],"obj":{"deep":true}}"""),
        )
    }

    @Test
    fun `empty containers stay compact`() {
        assertEquals(
            """{
  "a": [],
  "b": {},
  "c": [
    {}
  ]
}""",
            JsonFormat.tryFormat("""{"a":[],"b":{},"c":[{}]}"""),
        )
    }

    @Test
    fun `top level array`() {
        assertEquals(
            """[
  1,
  2
]""",
            JsonFormat.tryFormat("[1,2]"),
        )
    }

    @Test
    fun `rejects non json`() {
        assertFalse(JsonFormat.isJson("plain text"))
        try {
            JsonFormat.tryFormat("plain text")
            throw AssertionError("expected failure")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `rejects unbalanced input`() {
        try {
            JsonFormat.tryFormat("""{"a":1""")
            throw AssertionError("expected failure")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Unbalanced"))
        }
        try {
            JsonFormat.tryFormat("""{"a":1]}""")
            throw AssertionError("expected failure")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `rejects unterminated string`() {
        try {
            JsonFormat.tryFormat("""{"a":"b}""")
            throw AssertionError("expected failure")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Unterminated"))
        }
    }
}
