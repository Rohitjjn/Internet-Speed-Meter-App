package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every key in `values/strings.xml` must exist in `values-hi/strings.xml` (and vice
 * versa) with the **same format arguments** — a missing Hindi key falls back silently to
 * English, and a mismatched `%2$d` crashes at runtime. Reads the files straight from the
 * module directory (Gradle runs unit tests with `app/` as the working directory).
 */
class StringResourceParityTest {

    private fun keysWithArgs(path: String): Map<String, List<String>> {
        val file = listOf(File(path), File("app/$path")).firstOrNull { it.exists() }
            ?: error("strings file not found: $path (cwd=${File(".").absolutePath})")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val argPattern = Regex("%\\d+\\$[sdf]")
        val out = LinkedHashMap<String, List<String>>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            val name = node.attributes.getNamedItem("name").nodeValue
            assertTrue("duplicate key $name in $path", !out.containsKey(name))
            out[name] = argPattern.findAll(node.textContent).map { it.value }.sorted().toList()
        }
        return out
    }

    @Test
    fun englishAndHindiHaveIdenticalKeySets() {
        val en = keysWithArgs("src/main/res/values/strings.xml")
        val hi = keysWithArgs("src/main/res/values-hi/strings.xml")
        assertEquals("keys missing in values-hi: ${en.keys - hi.keys}", emptySet<String>(), en.keys - hi.keys)
        assertEquals("keys missing in values: ${hi.keys - en.keys}", emptySet<String>(), hi.keys - en.keys)
    }

    @Test
    fun everyTranslationUsesTheSameFormatArguments() {
        val en = keysWithArgs("src/main/res/values/strings.xml")
        val hi = keysWithArgs("src/main/res/values-hi/strings.xml")
        for ((key, args) in en) {
            assertEquals("format args differ for '$key'", args, hi[key])
        }
    }
}
