package com.indore.pathome.spaces.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.util.HtmlUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicPropertyHtmlServiceTest {
    private static final int MAXIMUM_SHELL_BYTES = 2 * 1024 * 1024;
    private static final String SHELL_PREFIX = "<!doctype html><html><head>";
    private static final String SHELL_SUFFIX =
            "<title data-pathome-shell-title>Pathome shell</title></head>"
                    + "<body><div id=\"root\"></div></body></html>";
    private static final Pattern TITLE_ELEMENT = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern DESCRIPTION_META = Pattern.compile(
            "<meta name=\"description\" content=\"(.*?)\" data-pathome-public-metadata=\"true\">",
            Pattern.DOTALL);
    private static final Pattern OG_TITLE_META = Pattern.compile(
            "<meta property=\"og:title\" content=\"(.*?)\" data-pathome-public-metadata=\"true\">",
            Pattern.DOTALL);
    private static final Pattern OG_DESCRIPTION_META = Pattern.compile(
            "<meta property=\"og:description\" content=\"(.*?)\" data-pathome-public-metadata=\"true\">",
            Pattern.DOTALL);
    private static final Pattern OG_URL_META = Pattern.compile(
            "<meta property=\"og:url\" content=\"(.*?)\" data-pathome-public-metadata=\"true\">",
            Pattern.DOTALL);
    private static final Pattern CANONICAL_LINK = Pattern.compile(
            "<link rel=\"canonical\" href=\"(.*?)\" data-pathome-public-metadata=\"true\">",
            Pattern.DOTALL);

    @Test
    void replacementMetacharactersRemainLiteralAcrossIndependentCanonicalFields() {
        String[] values = {
                "$", "$1", "${name}", "$0", "Sector\\West", "Sector\\\\West",
                "$1\\$0${name}\\\\$0", "Sector\\", "मूल्य $1 \\ पश्चिम"
        };

        for (int index = 0; index < values.length; index++) {
            String value = values[index];
            assertLiteralField(1000L + index, value, false, false);
            assertLiteralField(1100L + index, value, true, false);
            assertLiteralField(1200L + index, value, false, true);
        }
    }

    @Test
    void hostileHtmlAndReplacementCharactersRemainEscapedCanonicalText() {
        String title = "2 BHK \"Flat\" <script>onerror=$1</script> & $0 \\ home";
        String city = "Bhopal 'quoted' <b>$1</b> & West\\";
        String sector = "Area <img src=x> ${name} \\\\ $0\nEast";
        long id = 1301L;
        var readService = mock(PublicPropertyReadService.class);
        when(readService.findPublicSnapshot(id)).thenReturn(Optional.of(
                snapshot(id, title, city, sector, null, null)));
        var shell = new TrackingResource(validShell());
        var service = service(readService, shell.resource());

        var result = service.render(id);

        assertEquals(200, result.status());
        String html = result.html();
        assertEquals(title, HtmlUtils.htmlUnescape(extract(TITLE_ELEMENT, html))
                .replace(" | Pathome", ""));
        assertEquals(title, HtmlUtils.htmlUnescape(extract(OG_TITLE_META, html)));
        String description = "Property in " + sector + ", " + city;
        assertEquals(description, HtmlUtils.htmlUnescape(extract(DESCRIPTION_META, html)));
        assertEquals(description, HtmlUtils.htmlUnescape(extract(OG_DESCRIPTION_META, html)));
        assertEquals("https://pathome.test/property/" + id,
                HtmlUtils.htmlUnescape(extract(OG_URL_META, html)));
        assertEquals("https://pathome.test/property/" + id,
                HtmlUtils.htmlUnescape(extract(CANONICAL_LINK, html)));
        assertFalse(html.contains("<script>"));
        assertFalse(html.contains("<img src=x>"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("&amp;"));
        assertTrue(html.contains("&quot;"));
        assertEquals(1, occurrences(html, "<title>"));
        assertEquals(1, occurrences(html, "name=\"description\""));
        assertEquals(1, occurrences(html, "property=\"og:title\""));
        assertEquals(1, occurrences(html, "property=\"og:description\""));
        assertEquals(1, occurrences(html, "property=\"og:url\""));
        assertEquals(1, occurrences(html, "rel=\"canonical\""));
        assertEquals(1, shell.opened().size());
        assertEquals(1, shell.opened().get(0).closeCount());
    }

    @Test
    void propertyIdentityIsolationAndFailureRecoveryDoNotRetainFacts() {
        long firstId = 1401L;
        long secondId = 1402L;
        long failedId = 1403L;
        var first = snapshot(firstId, "First $1 \\ home", "City $0",
                "Locality\\West", null, null);
        var second = snapshot(secondId, "Second ${name}", "Other City",
                "Other $1", null, null);
        PublicPropertyReadService readService = mock(PublicPropertyReadService.class);
        when(readService.findPublicSnapshot(firstId)).thenReturn(Optional.of(first));
        when(readService.findPublicSnapshot(secondId)).thenReturn(Optional.of(second));
        when(readService.findPublicSnapshot(failedId))
                .thenThrow(new IllegalStateException("private-read-sentinel"));
        var shell = new TrackingResource(validShell());
        var service = service(readService, shell.resource());

        var firstResult = service.render(firstId);
        var secondResult = service.render(secondId);
        var thirdResult = service.render(firstId);
        var failedResult = service.render(failedId);
        var recoveredResult = service.render(firstId);

        assertEquals(List.of(200, 200, 200, 503, 200), List.of(
                firstResult.status(), secondResult.status(), thirdResult.status(),
                failedResult.status(), recoveredResult.status()));
        assertTrue(secondResult.html().contains("Second ${name}"));
        assertFalse(secondResult.html().contains("First $1"));
        assertTrue(thirdResult.html().contains("First $1"));
        assertFalse(thirdResult.html().contains("Second ${name}"));
        assertFalse(failedResult.html().contains("private-read-sentinel"));
        assertFalse(failedResult.html().contains("og:title"));
        assertTrue(recoveredResult.html().contains("First $1"));
        assertFalse(recoveredResult.html().contains("private-read-sentinel"));
    }

    @Test
    void maximumAndBelowMaximumShellsRenderAndCloseExactlyOnce() {
        int[] sizes = {MAXIMUM_SHELL_BYTES - 1, MAXIMUM_SHELL_BYTES};
        var shell = new TrackingResource(index -> new TrackingInputStream(
                shellOfSize(sizes[index]).getBytes(StandardCharsets.UTF_8)));
        var readService = constantlyAvailableSnapshot(1501L);
        var service = service(readService, shell.resource());

        assertEquals(200, service.render(1501L).status());
        assertEquals(200, service.render(1501L).status());
        assertEquals(2, shell.opened().size());
        for (int index = 0; index < shell.opened().size(); index++) {
            assertEquals(sizes[index], shell.opened().get(index).bytesRead());
            assertEquals(1, shell.opened().get(index).closeCount());
            assertTrue(shell.opened().get(index).bytesRead() <= MAXIMUM_SHELL_BYTES + 1);
        }
    }

    @Test
    void emptyMissingAndMissingSlotShellsFailClosedAndCloseOpenedStreams() {
        var readService = constantlyAvailableSnapshot(1601L);
        var empty = new TrackingResource("");
        var emptyResult = service(readService, empty.resource()).render(1601L);
        assertEquals(503, emptyResult.status());
        assertEquals(1, empty.opened().get(0).closeCount());
        assertFalse(emptyResult.html().contains("Property 1601"));

        AtomicBoolean missingOpened = new AtomicBoolean();
        Resource missing = resource(() -> {
            missingOpened.set(true);
            return new TrackingInputStream(new byte[0]);
        }, false);
        var missingResult = service(readService, missing).render(1601L);
        assertEquals(503, missingResult.status());
        assertFalse(missingOpened.get());
        assertFalse(missingResult.html().contains("Property 1601"));

        String noSlot = "<!doctype html><html><head></head><body><div id=\"root\"></div></body></html>";
        var missingSlot = new TrackingResource(noSlot);
        var missingSlotResult = service(readService, missingSlot.resource()).render(1601L);
        assertEquals(503, missingSlotResult.status());
        assertEquals(1, missingSlot.opened().get(0).closeCount());
        assertFalse(missingSlotResult.html().contains("Property 1601"));

        String duplicateSlot = SHELL_PREFIX + SHELL_SUFFIX + SHELL_SUFFIX;
        var duplicate = new TrackingResource(duplicateSlot);
        var duplicateResult = service(readService, duplicate.resource()).render(1601L);
        assertEquals(503, duplicateResult.status());
        assertEquals(1, duplicate.opened().get(0).closeCount());
        assertFalse(duplicateResult.html().contains("Property 1601"));
    }

    @Test
    void readAndCloseFailuresAreNeutralAndAlwaysAttemptCleanup() {
        var readService = constantlyAvailableSnapshot(1701L);
        byte[] prefix = (SHELL_PREFIX + "prefix").getBytes(StandardCharsets.UTF_8);
        var faulting = new FaultingInputStream(prefix, 7);
        var readFailure = resource(() -> faulting, true);

        var readFailureResult = service(readService, readFailure).render(1701L);

        assertEquals(503, readFailureResult.status());
        assertEquals(1, faulting.closeCount());
        assertFalse(readFailureResult.html().contains("fault-sentinel"));
        assertFalse(readFailureResult.html().contains("og:title"));

        var closeFailing = new CloseFailingInputStream(validShell().getBytes(StandardCharsets.UTF_8));
        var closeFailure = resource(() -> closeFailing, true);
        var closeFailureResult = service(readService, closeFailure).render(1701L);

        assertEquals(503, closeFailureResult.status());
        assertEquals(1, closeFailing.closeAttempts());
        assertFalse(closeFailureResult.html().contains("close-sentinel"));
        assertFalse(closeFailureResult.html().contains("og:title"));
    }

    @Test
    void shellReadsAreBoundedAtMaximumPlusOneAndOversizeRejects() {
        var readService = constantlyAvailableSnapshot(1801L);
        int oversize = MAXIMUM_SHELL_BYTES + 1;
        var atMaximum = new TrackingResource(index -> new TrackingInputStream(
                shellOfSize(MAXIMUM_SHELL_BYTES).getBytes(StandardCharsets.UTF_8)));
        var aboveMaximum = new TrackingResource(index -> new TrackingInputStream(
                shellOfSize(oversize).getBytes(StandardCharsets.UTF_8)));
        var muchLarger = new TrackingResource(index ->
                new RepeatingInputStream(MAXIMUM_SHELL_BYTES * 2L));

        assertEquals(200, service(readService, atMaximum.resource()).render(1801L).status());
        assertEquals(503, service(readService, aboveMaximum.resource()).render(1801L).status());
        assertEquals(503, service(readService, muchLarger.resource()).render(1801L).status());

        assertEquals(MAXIMUM_SHELL_BYTES, atMaximum.opened().get(0).bytesRead());
        assertEquals(oversize, aboveMaximum.opened().get(0).bytesRead());
        assertEquals(MAXIMUM_SHELL_BYTES + 1, muchLarger.opened().get(0).bytesRead());
        assertTrue(muchLarger.opened().get(0).bytesRead() <= MAXIMUM_SHELL_BYTES + 1);
        assertEquals(1, atMaximum.opened().get(0).closeCount());
        assertEquals(1, aboveMaximum.opened().get(0).closeCount());
        assertEquals(1, muchLarger.opened().get(0).closeCount());
    }

    @Test
    void repeatedSuccessAndFailureRequestsCloseEveryOpenedStream() {
        long id = 1901L;
        var readService = constantlyAvailableSnapshot(id);
        var alternating = new TrackingResource(index -> new TrackingInputStream(
                (index % 2 == 0
                        ? validShell()
                        : "<!doctype html><html><head></head>"
                                + "<body><div id=\"root\"></div></body></html>")
                        .getBytes(StandardCharsets.UTF_8)));
        var service = service(readService, alternating.resource());
        List<Integer> statuses = new ArrayList<>();

        for (int request = 0; request < 20; request++) {
            statuses.add(service.render(id).status());
        }

        for (int request = 0; request < statuses.size(); request++) {
            assertEquals(request % 2 == 0 ? 200 : 503, statuses.get(request));
        }
        assertEquals(20, alternating.opened().size());
        for (TrackingInputStream opened : alternating.opened()) {
            assertEquals(1, opened.closeCount());
            assertTrue(opened.bytesRead() <= MAXIMUM_SHELL_BYTES + 1);
        }
    }

    private static void assertLiteralField(long id, String value, boolean cityField,
                                          boolean sectorField) {
        String title = cityField || sectorField ? "2 BHK Flat" : value;
        String city = cityField ? value : "Indore";
        String sector = sectorField ? value : "Vijay Nagar";
        PublicPropertyReadService readService = mock(PublicPropertyReadService.class);
        when(readService.findPublicSnapshot(id))
                .thenReturn(Optional.of(snapshot(id, title, city, sector, null, null)));
        var shell = new TrackingResource(validShell());

        var result = service(readService, shell.resource()).render(id);

        assertEquals(200, result.status(), () -> "literal field failed: " + value);
        String html = result.html();
        assertEquals(title, HtmlUtils.htmlUnescape(extract(OG_TITLE_META, html)));
        assertEquals("Property in " + sector + ", " + city,
                HtmlUtils.htmlUnescape(extract(DESCRIPTION_META, html)));
        assertFalse(html.contains("Pathome shell"));
        assertEquals(1, occurrences(html, "property=\"og:title\""));
    }

    private static PublicPropertyReadService constantlyAvailableSnapshot(long id) {
        PublicPropertyReadService readService = mock(PublicPropertyReadService.class);
        when(readService.findPublicSnapshot(id)).thenAnswer(ignored -> Optional.of(
                snapshot(id, "Property " + id, "Indore", "Vijay Nagar", null, null)));
        return readService;
    }

    private static PublicPropertyReadService.PublicPropertySnapshot snapshot(
            long id, String title, String city, String sector, String bhk, BigDecimal rent) {
        return new PublicPropertyReadService.PublicPropertySnapshot(
                id, title, city, sector, bhk, null, rent, null);
    }

    private static PublicPropertyHtmlService service(
            PublicPropertyReadService readService, Resource resource) {
        ResourceLoader loader = new DefaultResourceLoader() {
            @Override
            public Resource getResource(String location) {
                return resource;
            }
        };
        return new PublicPropertyHtmlService(readService, "https://pathome.test", loader);
    }

    private static String validShell() {
        return SHELL_PREFIX + SHELL_SUFFIX;
    }

    private static String shellOfSize(int size) {
        int padding = size - SHELL_PREFIX.length() - SHELL_SUFFIX.length();
        if (padding < 0) throw new IllegalArgumentException("Requested shell size is too small");
        return SHELL_PREFIX + " ".repeat(padding) + SHELL_SUFFIX;
    }

    private static String extract(Pattern pattern, String html) {
        Matcher matcher = pattern.matcher(html);
        assertTrue(matcher.find(), () -> "Missing metadata element: " + pattern);
        return matcher.group(1);
    }

    private static int occurrences(String value, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }

    private static Resource resource(Supplier<InputStream> streams, boolean exists) {
        return new StreamResource(streams, exists);
    }

    private static final class StreamResource extends AbstractResource {
        private final Supplier<InputStream> streams;
        private final boolean exists;

        private StreamResource(Supplier<InputStream> streams, boolean exists) {
            this.streams = streams;
            this.exists = exists;
        }

        @Override
        public boolean exists() {
            return exists;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return streams.get();
        }

        @Override
        public String getDescription() {
            return "synthetic public property shell";
        }
    }

    private static final class TrackingResource {
        private final IntFunction<InputStream> streams;
        private final List<TrackingInputStream> opened = new ArrayList<>();

        private TrackingResource(String shell) {
            this(index -> new TrackingInputStream(shell.getBytes(StandardCharsets.UTF_8)));
        }

        private TrackingResource(IntFunction<InputStream> streams) {
            this.streams = streams;
        }

        private Resource resource() {
            return new StreamResource(() -> {
                InputStream stream = streams.apply(opened.size());
                if (!(stream instanceof TrackingInputStream tracking)) {
                    throw new IllegalStateException("Tracking resource requires a tracking stream");
                }
                opened.add(tracking);
                return tracking;
            }, true);
        }

        private List<TrackingInputStream> opened() {
            return opened;
        }
    }

    private static class TrackingInputStream extends InputStream {
        private final ByteArrayInputStream delegate;
        private int bytesRead;
        private int closeCount;

        private TrackingInputStream(byte[] bytes) {
            delegate = new ByteArrayInputStream(bytes);
        }

        @Override
        public synchronized int read(byte[] bytes, int offset, int length) throws IOException {
            int read = delegate.read(bytes, offset, length);
            if (read > 0) recordBytesRead(read);
            return read;
        }

        @Override
        public synchronized int read() {
            int read = delegate.read();
            if (read >= 0) recordBytesRead(1);
            return read;
        }

        @Override
        public void close() throws IOException {
            closeCount++;
            delegate.close();
        }

        protected void recordBytesRead(int count) {
            bytesRead += count;
        }

        protected int bytesRead() {
            return bytesRead;
        }

        protected final int closeCount() {
            return closeCount;
        }
    }

    private static final class RepeatingInputStream extends TrackingInputStream {
        private final long maximumBytes;
        private long position;

        private RepeatingInputStream(long maximumBytes) {
            super(new byte[0]);
            this.maximumBytes = maximumBytes;
        }

        @Override
        public synchronized int read(byte[] bytes, int offset, int length) throws IOException {
            if (position >= maximumBytes) return -1;
            int count = (int) Math.min(length, maximumBytes - position);
            Arrays.fill(bytes, offset, offset + count, (byte) ' ');
            position += count;
            recordBytesRead(count);
            return count;
        }

        @Override
        public synchronized int read() {
            if (position >= maximumBytes) return -1;
            position++;
            recordBytesRead(1);
            return ' ';
        }
    }

    private static final class FaultingInputStream extends TrackingInputStream {
        private final int failureOffset;

        private FaultingInputStream(byte[] bytes, int failureOffset) {
            super(bytes);
            this.failureOffset = failureOffset;
        }

        @Override
        public synchronized int read(byte[] bytes, int offset, int length) throws IOException {
            if (bytesRead() >= failureOffset) throw new IOException("fault-sentinel");
            int allowed = Math.min(length, failureOffset - bytesRead());
            return super.read(bytes, offset, allowed);
        }
    }

    private static final class CloseFailingInputStream extends TrackingInputStream {
        private int closeAttempts;

        private CloseFailingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closeAttempts++;
            throw new IOException("close-sentinel");
        }

        private int closeAttempts() {
            return closeAttempts;
        }
    }
}
