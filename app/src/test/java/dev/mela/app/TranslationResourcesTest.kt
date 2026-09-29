package dev.mela.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class TranslationResourcesTest {
    private fun resources(language: String): Map<String, String> {
        val file = File("src/main/res/$language/strings.xml")
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement.childNodes
        val values = mutableMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as? Element ?: continue
            val name = element.getAttribute("name")
            if (element.tagName == "plurals") {
                val items = element.getElementsByTagName("item")
                for (j in 0 until items.length) {
                    val item = items.item(j) as Element
                    assertNull("Duplicate $name", values.put("$name/${item.getAttribute("quantity")}", item.textContent))
                }
            } else assertNull("Duplicate $name", values.put(name, element.textContent))
        }
        return values
    }
    @Test fun italianCoversEveryStringAndPreservesFormatArguments() {
        val english = resources("values")
        val italian = resources("values-it")
        assertEquals(english.keys, italian.keys.filterNot { it.endsWith("/many") }.toSet())
        // Italian has a CLDR "many" form for exact millions; English does not.
        italian.filterKeys { it.endsWith("/many") }.forEach { (key, value) ->
            assertEquals(italian[key.removeSuffix("/many") + "/other"], value)
        }
        val arguments = Regex("%(?:[0-9]+\\$)?[dsf]")
        english.forEach { (key, value) ->
            val translation = requireNotNull(italian[key])
            assertTrue("Empty Italian translation for $key", translation.isNotBlank())
            assertEquals("Format arguments for $key", arguments.findAll(value).map { it.value }.sorted().toList(),
                arguments.findAll(translation).map { it.value }.sorted().toList())
        }
    }
}
