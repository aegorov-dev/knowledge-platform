package com.aegorov.knowledgeplatform.indexingservice.application;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultDocumentTextExtractorTest {

    private final DefaultDocumentTextExtractor textExtractor = new DefaultDocumentTextExtractor();

    @Test
    void extractsEmptyTextFromEmptyDocument() {
        String extractedText = textExtractor.extract(new byte[0], "text/plain", "empty.txt");

        assertEquals("", extractedText);
    }

    @Test
    void extractsPlainTextAsUtf8() {
        String documentContent = "Hello, World";

        String extractedText = textExtractor.extract(documentContent.getBytes(StandardCharsets.UTF_8), "text/plain", "document.txt");

        assertEquals(documentContent, extractedText);
    }

    @Test
    void treatsMissingContentTypeAsPlainText() {
        String documentContent = "Hello, World!";
        byte[] content = documentContent.getBytes(StandardCharsets.UTF_8);

        assertEquals(documentContent, textExtractor.extract(content, "", "document.txt"));
        assertEquals(documentContent, textExtractor.extract(content, null, "document.txt"));
    }

    @Test
    void extractsPdfByContentType() throws IOException {
        byte[] pdf = createPdf("Hello from PDF");

        String extractedText = textExtractor.extract(pdf, "APPLICATION/PDF", "document");

        assertTrue(extractedText.contains("Hello from PDF"));
    }

    @Test
    void extractsPdfByFileExtension() throws IOException {
        byte[] pdf = createPdf("Hello from PDF");

        String extractedText = textExtractor.extract(pdf, "application/octet-stream", "Document.PDF");

        assertTrue(extractedText.contains("Hello from PDF"));
    }

    @Test
    void throwsWhenPdfIsInvalid() {
        byte[] content = "not a pdf".getBytes(StandardCharsets.UTF_8);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> textExtractor.extract(content, "application/pdf", "broken.pdf"));
        assertEquals("Failed to parse PDF document", ex.getMessage());
    }

    private static byte[] createPdf(String text) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(text);
                stream.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
