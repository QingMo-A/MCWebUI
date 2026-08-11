package dev.qingmo.mcwebui.target.neoforge1211;

import org.cef.callback.CefCallback;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.RecordingCefResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McuiSchemeHandlerTest {
    @Test
    void servesHtmlJavascriptAndCssWithExactLengthAndMime() throws Exception {
        assertResource("mcui://playground.mcwebui/index.html", "text/html", "web/playground/index.html");
        assertResource("mcui://playground.mcwebui/assets/app.js", "text/javascript", "web/playground/assets/app.js");
        assertResource("mcui://playground.mcwebui/assets/app.css", "text/css", "web/playground/assets/app.css");
    }

    @Test
    void missingAndUnsafePathsReturnNotFoundWithoutBody() {
        assertNotFound("mcui://playground.mcwebui/missing.html");
        assertNotFound("mcui://playground.mcwebui/../secret");
        assertNotFound("mcui://playground.mcwebui/%2e%2e/secret");
        assertNotFound("mcui://playground/index.html");
        assertNotFound("https://playground.mcwebui/index.html");
    }

    private static void assertResource(String url, String mime, String resource) throws Exception {
        ResponseData response = read(url);
        byte[] expected;
        try (InputStream stream = McuiSchemeHandlerTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            expected = stream.readAllBytes();
        }
        assertEquals(200, response.status());
        assertEquals("OK", response.statusText());
        assertEquals(mime, response.mime());
        assertEquals(expected.length, response.contentLength());
        assertArrayEquals(expected, response.body());
    }

    private static void assertNotFound(String url) {
        ResponseData response = read(url);
        assertEquals(404, response.status());
        assertEquals("Not Found", response.statusText());
        assertEquals(0, response.contentLength());
        assertEquals("text/plain", response.mime());
        assertEquals(0, response.body().length);
    }

    private static ResponseData read(String url) {
        McuiSchemeHandler handler = new McuiSchemeHandler(url);
        RecordingCallback callback = new RecordingCallback();
        assertTrue(handler.processRequest(null, callback));
        assertTrue(callback.continued);

        RecordingCefResponse response = new RecordingCefResponse();
        IntRef contentLength = new IntRef();
        handler.getResponseHeaders(response, contentLength, new StringRef());

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] chunk = new byte[7];
        while (true) {
            IntRef bytesRead = new IntRef();
            boolean more = handler.readResponse(chunk, chunk.length, bytesRead, callback);
            if (bytesRead.get() > 0) body.write(chunk, 0, bytesRead.get());
            if (!more) break;
        }
        return new ResponseData(response.status(), response.statusText(), response.mime(),
                contentLength.get(), body.toByteArray());
    }

    private record ResponseData(int status, String statusText, String mime, int contentLength, byte[] body) { }

    private static final class RecordingCallback implements CefCallback {
        private boolean continued;
        private boolean cancelled;

        @Override public void Continue() { continued = true; }
        @Override public void cancel() { cancelled = true; }
    }

}
