package me.lvca.alba.app

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

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
        // The grouping flag counts as part of the argument: "1.657" in Italian must stay "1,657" in English.
        val arguments = Regex("%(?:[0-9]+\\$)?,?[dsf]")
        english.forEach { (key, value) ->
            val translation = requireNotNull(italian[key])
            assertTrue("Empty Italian translation for $key", translation.isNotBlank())
            assertEquals("Format arguments for $key", arguments.findAll(value).map { it.value }.sorted().toList(),
                arguments.findAll(translation).map { it.value }.sorted().toList())
        }
    }

    /** Every count is grouped for the reader's language ("12,607", "12.607"); a plural's number is always a count. */
    @Test fun pluralCountsUseTheGroupingFlag() {
        val ungrouped = Regex("%(?:[0-9]+\\$)?d")
        for (language in listOf("values", "values-it")) {
            val counts = resources(language).filter { (key, value) -> "/" in key && ungrouped.containsMatchIn(value) }
            assertTrue("Counts without grouping in $language: ${counts.keys}", counts.isEmpty())
        }
    }
}
