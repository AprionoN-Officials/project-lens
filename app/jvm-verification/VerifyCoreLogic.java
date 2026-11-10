import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Standalone OpenJDK 17 Verification & Benchmark Suite for Project Asisten (Project Lens v0.1).
 * Executes directly on the local JVM without external network dependencies, verifying:
 * 1. CropMath coordinate normalization, clamping, and View-to-Bitmap scaling.
 * 2. OcrLayoutAssembler reading-order sorting and word/line/multi-line text assembly.
 * 3. BitmapSecurityInspector FLAG_SECURE black-frame detection.
 * 4. SensitiveAppGuard banking/wallet package protection.
 * 5. XML well-formedness and security invariants across AndroidManifest.xml and res/ XML files.
 */
public class VerifyCoreLogic {

    static int passedTests = 0;
    static int failedTests = 0;

    static void assertTrue(String name, boolean condition) {
        if (condition) {
            passedTests++;
            System.out.println("  [PASS] " + name);
        } else {
            failedTests++;
            System.err.println("  [FAIL] " + name);
        }
    }

    static void assertEquals(String name, Object expected, Object actual) {
        if ((expected == null && actual == null) || (expected != null && expected.equals(actual))) {
            passedTests++;
            System.out.println("  [PASS] " + name + " -> " + actual);
        } else {
            failedTests++;
            System.err.println("  [FAIL] " + name + " (expected: " + expected + ", actual: " + actual + ")");
        }
    }

    // Mirror of CropMath logic
    static int[] normalizeAndClamp(float x1, float y1, float x2, float y2, int maxW, int maxH, int minSize) {
        if (maxW <= 0 || maxH <= 0) return new int[]{0, 0, 0, 0};
        int safeMin = Math.min(minSize, Math.min(maxW, maxH));
        int left = Math.max(0, Math.min(maxW, Math.round(Math.min(x1, x2))));
        int right = Math.max(0, Math.min(maxW, Math.round(Math.max(x1, x2))));
        int top = Math.max(0, Math.min(maxH, Math.round(Math.min(y1, y2))));
        int bottom = Math.max(0, Math.min(maxH, Math.round(Math.max(y1, y2))));

        if (right - left < safeMin) {
            int center = (left + right) / 2;
            left = Math.max(0, Math.min(maxW - safeMin, center - safeMin / 2));
            right = left + safeMin;
        }
        if (bottom - top < safeMin) {
            int center = (top + bottom) / 2;
            top = Math.max(0, Math.min(maxH - safeMin, center - safeMin / 2));
            bottom = top + safeMin;
        }
        return new int[]{left, top, right, bottom};
    }

    // Mirror of BitmapSecurityInspector
    static boolean areSampledPixelsAllBlankOrBlack(List<Integer> samples) {
        if (samples.isEmpty()) return true;
        for (int pixel : samples) {
            int alpha = (pixel >>> 24) & 0xFF;
            if (alpha <= 10) continue;
            int r = (pixel >>> 16) & 0xFF;
            int g = (pixel >>> 8) & 0xFF;
            int b = pixel & 0xFF;
            if (r > 3 || g > 3 || b > 3) return false;
        }
        return true;
    }

    // Mirror of SensitiveAppGuard
    static boolean isSensitivePackage(String pkg) {
        if (pkg == null || pkg.isBlank()) return false;
        String norm = pkg.trim().toLowerCase();
        String[] prefixes = {
            "com.bca", "id.co.bri", "com.mandiri", "src.com.bni", "id.co.btn",
            "com.cimbniaga", "com.permata", "com.danamon", "com.btpn.dc",
            "com.jago.digitalbanking", "id.co.bankbsi", "id.dana", "ovo.id",
            "com.gojek.gopay", "com.telkom.mwallet", "id.co.seabank"
        };
        for (String p : prefixes) {
            if (norm.startsWith(p)) return true;
        }
        String[] keywords = {"bank", "mbanking", "wallet", "authenticator", "keyguard", "biometric", "permissioncontroller"};
        for (String kw : keywords) {
            if (norm.contains(kw)) return true;
        }
        return false;
    }

    static class Word {
        final int id;
        final String text;
        final int left, top, right, bottom;
        Word(int id, String text, int left, int top, int right, int bottom) {
            this.id = id;
            this.text = text;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    static class Line {
        final int id;
        final int left, top, right, bottom;
        final List<Word> words;
        Line(int id, int left, int top, int right, int bottom, List<Word> words) {
            this.id = id;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.words = words;
        }
    }

    static String assembleSelectedText(List<Line> lines, Set<Integer> selectedIds) {
        List<Line> sorted = new ArrayList<>(lines);
        sorted.sort((a, b) -> {
            int aCy = (a.top + a.bottom) / 2;
            int bCy = (b.top + b.bottom) / 2;
            int avgH = Math.max(2, (a.bottom - a.top) + (b.bottom - b.top)) / 2;
            if (Math.abs(aCy - bCy) <= avgH * 0.45f) {
                return Integer.compare(a.left, b.left);
            }
            return Integer.compare(a.top, b.top);
        });

        List<String> outLines = new ArrayList<>();
        for (Line line : sorted) {
            List<Word> selectedWords = new ArrayList<>();
            for (Word w : line.words) {
                if (selectedIds.contains(w.id)) selectedWords.add(w);
            }
            selectedWords.sort((w1, w2) -> Integer.compare(w1.left, w2.left));
            if (!selectedWords.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < selectedWords.size(); i++) {
                    if (i > 0) sb.append(" ");
                    sb.append(selectedWords.get(i).text.trim());
                }
                outLines.add(sb.toString());
            }
        }
        return String.join("\n", outLines);
    }

    static void collectXmlFiles(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) collectXmlFiles(f, out);
            else if (f.getName().endsWith(".xml")) out.add(f);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Project Asisten (Project Lens v0.1) Automated JVM Verification ===");
        long startNs = System.nanoTime();

        // 1. CropMath tests
        int[] rect1 = normalizeAndClamp(-40f, 1500f, 400f, 100f, 1080, 2400, 24);
        assertEquals("CropMath clamps negative X and inverted Y", "[0, 100, 400, 1500]", Arrays.toString(rect1));

        int[] rect2 = normalizeAndClamp(500f, 500f, 501f, 501f, 1080, 2400, 40);
        assertEquals("CropMath enforces minSizePx on tiny tap", 40, rect2[2] - rect2[0]);
        assertEquals("CropMath enforces minSizePx height", 40, rect2[3] - rect2[1]);

        // 2. OCR reading order and selection tests
        Line bottomLine = new Line(1, 50, 400, 600, 450, Arrays.asList(
            new Word(1, "Terjemahkan", 50, 400, 250, 450),
            new Word(2, "layar", 260, 400, 380, 450),
            new Word(3, "aktif", 390, 400, 500, 450)
        ));
        Line topLine = new Line(2, 50, 120, 500, 170, Arrays.asList(
            new Word(4, "Project", 50, 120, 200, 170),
            new Word(5, "Asisten", 210, 120, 380, 170)
        ));
        Set<Integer> allIds = new HashSet<>(Arrays.asList(1, 2, 3, 4, 5));
        String fullText = assembleSelectedText(Arrays.asList(bottomLine, topLine), allIds);
        assertEquals("OcrLayoutAssembler sorts lines top-to-bottom and preserves newline",
            "Project Asisten\nTerjemahkan layar aktif", fullText);

        Set<Integer> singleWord = new HashSet<>(Collections.singletonList(2));
        assertEquals("OcrLayoutAssembler single word selection", "layar",
            assembleSelectedText(Arrays.asList(bottomLine, topLine), singleWord));

        // 3. FLAG_SECURE black frame detection tests
        List<Integer> blackFrame = new ArrayList<>(Collections.nCopies(144, 0xFF000000));
        assertTrue("BitmapSecurityInspector detects all-black FLAG_SECURE frame",
            areSampledPixelsAllBlankOrBlack(blackFrame));

        List<Integer> validFrame = new ArrayList<>(blackFrame);
        validFrame.set(72, 0xFF0B57D0);
        assertTrue("BitmapSecurityInspector accepts non-black normal screenshot frame",
            !areSampledPixelsAllBlankOrBlack(validFrame));

        // 4. Banking & Sensitive package guard tests
        assertTrue("SensitiveAppGuard blocks BCA Mobile", isSensitivePackage("com.bca"));
        assertTrue("SensitiveAppGuard blocks BRImo", isSensitivePackage("id.co.bri.brimo"));
        assertTrue("SensitiveAppGuard blocks Livin Mandiri", isSensitivePackage("com.mandiri.livin"));
        assertTrue("SensitiveAppGuard blocks DANA wallet", isSensitivePackage("id.dana"));
        assertTrue("SensitiveAppGuard allows Chrome browser", !isSensitivePackage("com.android.chrome"));

        // 5. Validate all XML resources & AndroidManifest security rules
        File mainDir = new File("app/src/main");
        List<File> xmlFiles = new ArrayList<>();
        collectXmlFiles(mainDir, xmlFiles);
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        int validXmlCount = 0;
        for (File xml : xmlFiles) {
            dbf.newDocumentBuilder().parse(xml);
            validXmlCount++;
        }
        assertEquals("All Android XML resource & manifest files are well-formed", 21, validXmlCount);

        // 6. Security & Privacy Manifest Audit
        String manifestText = java.nio.file.Files.readString(new File("app/src/main/AndroidManifest.xml").toPath());
        assertTrue("Manifest does NOT request SYSTEM_ALERT_WINDOW permission",
            !manifestText.contains("android.permission.SYSTEM_ALERT_WINDOW"));
        assertTrue("Manifest does NOT request CAMERA permission",
            !manifestText.contains("android.permission.CAMERA"));
        assertTrue("Manifest does NOT request QUERY_ALL_PACKAGES permission",
            !manifestText.contains("android.permission.QUERY_ALL_PACKAGES"));

        String accConfigText = java.nio.file.Files.readString(
            new File("app/src/main/res/xml/accessibility_service_config.xml").toPath());
        assertTrue("Accessibility config has empty accessibilityEventTypes (zero background monitoring)",
            accConfigText.contains("android:accessibilityEventTypes=\"\""));
        assertTrue("Accessibility config sets canRetrieveWindowContent=false (cannot read PIN/OTP/passwords)",
            accConfigText.contains("android:canRetrieveWindowContent=\"false\""));
        assertTrue("Accessibility config sets canPerformGestures=false",
            accConfigText.contains("android:canPerformGestures=\"false\""));

        long elapsedUs = (System.nanoTime() - startNs) / 1_000L;
        Runtime rt = Runtime.getRuntime();
        long usedHeapKb = (rt.totalMemory() - rt.freeMemory()) / 1024L;

        System.out.println("\n=== Real Measured JVM Verification Metrics ===");
        System.out.println("  Total Tests Run    : " + (passedTests + failedTests));
        System.out.println("  Passed             : " + passedTests);
        System.out.println("  Failed             : " + failedTests);
        System.out.println("  Execution Time     : " + elapsedUs + " us (" + (elapsedUs / 1000.0) + " ms)");
        System.out.println("  JVM Used Heap      : " + usedHeapKb + " KB");

        if (failedTests > 0) {
            System.exit(1);
        }
    }
}
