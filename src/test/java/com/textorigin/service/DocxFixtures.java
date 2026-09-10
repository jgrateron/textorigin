package com.textorigin.service;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * Documentos DOCX generados en memoria para los tests de extracción: sin binarios en el
 * repositorio y con control total sobre el formato oculto.
 */
final class DocxFixtures {

    private DocxFixtures() {
    }

    /** DOCX con un párrafo sin nada oculto. */
    static byte[] plain(String text) throws IOException {
        return docx(document -> document.createParagraph().createRun().setText(text));
    }

    /** DOCX con un run visible y otro oculto con {@code w:vanish}. */
    static byte[] withVanishRun(String visible, String hidden) throws IOException {
        return docx(document -> {
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.createRun().setText(visible);
            XWPFRun hiddenRun = paragraph.createRun();
            hiddenRun.setText(hidden);
            hiddenRun.setVanish(true);
        });
    }

    /** DOCX con un run visible y dos ocultos: uno en blanco y otro de un punto. */
    static byte[] withWhiteAndTinyRuns(String visible, String hiddenText) throws IOException {
        return docx(document -> {
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.createRun().setText(visible);
            XWPFRun whiteRun = paragraph.createRun();
            whiteRun.setText(hiddenText);
            whiteRun.setColor("FFFFFF");
            XWPFRun tinyRun = paragraph.createRun();
            tinyRun.setText(hiddenText);
            tinyRun.setFontSize(1);
        });
    }

    /** DOCX con el texto oculto dentro de la celda de una tabla. */
    static byte[] withHiddenRunInTable(String visible, String hidden) throws IOException {
        return docx(document -> {
            XWPFTable table = document.createTable(1, 1);
            XWPFTableCell cell = table.getRow(0).getCell(0);
            XWPFParagraph paragraph = cell.getParagraphs().getFirst();
            paragraph.createRun().setText(visible);
            XWPFRun hiddenRun = paragraph.createRun();
            hiddenRun.setText(hidden);
            hiddenRun.setVanish(true);
        });
    }

    private static byte[] docx(Consumer<XWPFDocument> builder) throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            builder.accept(document);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.write(output);
            return output.toByteArray();
        }
    }
}
