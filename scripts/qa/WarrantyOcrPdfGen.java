// 합성 하자보증보험 PDF 생성기 — scripts/qa/warranty_ocr_e2e.py가 JDK 컨테이너(네트워크 없음)에서 실행한다.
// notification-service 이미지에 이미 들어 있는 PDFBox 3 jar만 classpath로 쓰며 새 의존성은 없다.
// 사용: java -cp '<pdfbox libs>/*' WarrantyOcrPdfGen.java <font.ttf> <out-dir> <spec.json 경로>
// spec: 한 줄당 "kind\t파일명\t보험사\t증권번호\t시작일\t종료일" (kind = text | image)
//  - text : 한글 폰트를 내장한 텍스트 층 1쪽 (PDFBox 텍스트 추출 경로)
//  - image: 같은 내용을 300 DPI 흑백 래스터로만 그린 1쪽, 텍스트 층 없음 (Tesseract 경로)
// 생성 파일은 실행 임시 디렉터리에만 남고 저장소에 커밋하지 않는다.

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public class WarrantyOcrPdfGen {

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        File font = new File(args[0]);
        File outDir = new File(args[1]);
        List<String> specs = Files.readAllLines(new File(args[2]).toPath(), StandardCharsets.UTF_8);
        for (String spec : specs) {
            if (spec.isBlank()) continue;
            String[] f = spec.split("\t");
            List<String> lines = List.of(
                    "하자이행 보증보험 증권 (합성 QA 문서)",
                    "보험회사: " + f[2],
                    "증권번호: " + f[3],
                    "보증기간: " + f[4] + " ~ " + f[5],
                    "보증금액: 1,000,000원",
                    "피보험자: QA 합성 현장");
            File out = new File(outDir, f[1]);
            if ("text".equals(f[0])) {
                writeText(font, lines, out);
            } else if ("image".equals(f[0])) {
                writeImage(font, lines, out);
            } else {
                throw new IllegalArgumentException("unknown kind");
            }
            // 앱과 같은 PDFBox 텍스트 추출로 텍스트 층 글자 수를 다시 센다(image는 0이어야 Tesseract 경로).
            int textChars;
            try (PDDocument check = Loader.loadPDF(out)) {
                textChars = new PDFTextStripper().getText(check).strip().length();
            }
            System.out.println("generated\t" + f[0] + "\t" + out.length() + "\t" + textChars);
        }
    }

    private static void writeText(File fontFile, List<String> lines, File out) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDType0Font font = PDType0Font.load(doc, fontFile);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = 770;
                for (String line : lines) {
                    cs.beginText();
                    cs.setFont(font, 14);
                    cs.newLineAtOffset(60, y);
                    cs.showText(line);
                    cs.endText();
                    y -= 32;
                }
            }
            doc.save(out);
        }
    }

    private static void writeImage(File fontFile, List<String> lines, File out) throws Exception {
        // A4 @300 DPI. 앱도 300 DPI로 렌더링하므로 원본 해상도 그대로 Tesseract에 전달된다.
        int width = 2480, height = 3508;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setColor(Color.BLACK);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Font font = Font.createFont(Font.TRUETYPE_FONT, fontFile).deriveFont(Font.PLAIN, 58f);
            g.setFont(font);
            int y = 330;
            for (String line : lines) {
                g.drawString(line, 250, y);
                y += 135;
            }
        } finally {
            g.dispose();
        }
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDImageXObject image = LosslessFactory.createFromImage(doc, img);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(image, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
            }
            doc.save(out);
        }
    }
}
