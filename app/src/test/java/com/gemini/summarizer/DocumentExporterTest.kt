package com.gemini.summarizer

import com.gemini.summarizer.core.export.DocumentExporter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentExporterTest {

    @Test
    fun testDocxXmlDeclarationStartsExactlyWithXmlHeader() {
        val testBodyXml = "<w:p><w:r><w:t>Sample</w:t></w:r></w:p>"
        val documentXml = DocumentExporter.createDocumentXml(testBodyXml)

        // Strict assertion: XML must start exactly at index 0 with <?xml without any whitespace or newline
        assertTrue("Document XML must start with <?xml", documentXml.startsWith("<?xml"))
        assertFalse("Document XML must not have leading space", documentXml.startsWith(" "))
        assertFalse("Document XML must not have leading newline", documentXml.startsWith("\n"))
        assertTrue("Document XML must contain body content", documentXml.contains(testBodyXml))
    }
}
