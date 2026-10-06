package br.pucpr.prissma_server.seed;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Gera em memória os arquivos da seed: PNG (fotos, plantas, versões de
 * proposta), PDF e DOCX. As telas de propostas e do diário renderizam a imagem
 * inline, então precisam de bytes de imagem de verdade, não só de uma linha em
 * attachments apontando para o nada.
 */
final class SeedFiles {

    record GeneratedFile(byte[] bytes, String extension, String contentType) {
    }

    private SeedFiles() {
    }

    /** Imagem 1200x800 com gradiente, uma "cena" simples e o rótulo no centro. */
    static GeneratedFile png(String label, Color base) {
        int width = 1200;
        int height = 800;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g.setPaint(new GradientPaint(0, 0, base.brighter(), width, height, base.darker()));
            g.fillRect(0, 0, width, height);

            // Piso e parede: dá cara de ambiente sem depender de asset externo.
            g.setColor(new Color(255, 255, 255, 40));
            g.fillRect(0, height * 2 / 3, width, height / 3);
            g.setColor(new Color(255, 255, 255, 70));
            g.setStroke(new BasicStroke(6f));
            g.drawRect(160, 140, 360, 300);
            g.drawRect(700, 220, 300, 320);
            g.drawLine(0, height * 2 / 3, width, height * 2 / 3);

            drawLabel(g, label, width, height);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new GeneratedFile(out.toByteArray(), "png", "image/png");
    }

    /** Planta esquemática: fundo claro, cômodos em traço e o rótulo. */
    static GeneratedFile floorPlanPng(String label) {
        int width = 1200;
        int height = 800;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0xF4F1EA));
            g.fillRect(0, 0, width, height);
            g.setColor(new Color(0x2F3B52));
            g.setStroke(new BasicStroke(8f));
            g.drawRect(100, 100, 1000, 600);
            g.drawLine(500, 100, 500, 450);
            g.drawLine(100, 450, 800, 450);
            g.drawLine(800, 100, 800, 700);
            g.setStroke(new BasicStroke(3f));
            g.setColor(new Color(0xC0392B));
            g.drawRect(520, 120, 260, 310);

            drawLabel(g, label, width, height);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new GeneratedFile(out.toByteArray(), "png", "image/png");
    }

    static GeneratedFile pdf(String title, String body) {
        String xhtml = """
                <html><head><style>
                  body { font-family: sans-serif; margin: 48px; color: #1f2933; }
                  h1 { font-size: 22px; border-bottom: 2px solid #1f2933; padding-bottom: 8px; }
                  p { font-size: 13px; line-height: 1.6; }
                  .muted { color: #6b7280; font-size: 11px; }
                </style></head><body>
                  <h1>%s</h1>
                  <p>%s</p>
                  <p class="muted">Documento fictício gerado pela seed de desenvolvimento do PRISSMA.</p>
                </body></html>
                """.formatted(escapeXml(title), escapeXml(body));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.withHtmlContent(xhtml, null);
            builder.toStream(out);
            builder.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new GeneratedFile(out.toByteArray(), "pdf", "application/pdf");
    }

    /** DOCX mínimo válido: três partes do pacote OOXML e um parágrafo por linha. */
    static GeneratedFile docx(String title, String body) {
        String paragraphs = paragraph(title, true) + paragraph(body, false);
        String document = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>%s</w:body></w:document>""".formatted(paragraphs);
        String contentTypes = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>""";
        String rels = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                </Relationships>""";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            putEntry(zip, "[Content_Types].xml", contentTypes);
            putEntry(zip, "_rels/.rels", rels);
            putEntry(zip, "word/document.xml", document);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new GeneratedFile(out.toByteArray(), "docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    private static void drawLabel(Graphics2D g, String label, int width, int height) {
        // Em container sem fontes instaladas o AWT pode falhar ao desenhar texto;
        // a imagem continua válida sem o rótulo.
        try {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 54));
            int textWidth = g.getFontMetrics().stringWidth(label);
            int x = Math.max(40, (width - textWidth) / 2);
            int y = height / 2;
            g.setColor(new Color(0, 0, 0, 110));
            g.fillRoundRect(x - 30, y - 70, Math.min(textWidth + 60, width - 20), 100, 24, 24);
            g.setColor(Color.WHITE);
            g.drawString(label, x, y);
        } catch (RuntimeException | InternalError | LinkageError ignored) {
            // sem rótulo
        }
    }

    private static String paragraph(String text, boolean bold) {
        String run = bold ? "<w:rPr><w:b/></w:rPr>" : "";
        return "<w:p><w:r>" + run + "<w:t xml:space=\"preserve\">" + escapeXml(text) + "</w:t></w:r></w:p>";
    }

    private static void putEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
