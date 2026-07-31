package io.quarkiverse.mcp.servers.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.vertx.core.http.HttpServerRequest;

/**
 * Unit tests for the per-request credential header parsing.
 *
 * These run without booting Quarkus - {@link HttpHeaderParameterHelper} is a plain static
 * helper and the only collaborator is the Vert.x request, which is mocked.
 */
class HttpHeaderParameterHelperTest {

    private static final String[] JDBC_HEADERS = { "x-jdbc-url", "x-jdbc-user", "x-jdbc-password" };

    private static HttpServerRequest requestWith(String... headerPairs) {
        HttpServerRequest request = mock(HttpServerRequest.class);
        // Unstubbed headers return null, matching Vert.x behaviour for absent headers.
        for (int i = 0; i < headerPairs.length; i += 2) {
            when(request.getHeader(headerPairs[i])).thenReturn(headerPairs[i + 1]);
        }
        return request;
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Nested
    @DisplayName("individual headers")
    class IndividualHeaders {

        @Test
        @DisplayName("returns values in the order the header names were requested")
        void returnsValuesInRequestedOrder() {
            HttpServerRequest request = requestWith(
                    "x-jdbc-url", "jdbc:h2:mem:test",
                    "x-jdbc-user", "sa",
                    "x-jdbc-password", "secret");

            assertArrayEquals(
                    new String[] { "jdbc:h2:mem:test", "sa", "secret" },
                    HttpHeaderParameterHelper.getHeaderParameters(request, JDBC_HEADERS));
        }

        @Test
        @DisplayName("absent headers come back as null rather than empty strings")
        void absentHeadersAreNull() {
            HttpServerRequest request = requestWith("x-jdbc-url", "jdbc:h2:mem:test");

            String[] parameters = HttpHeaderParameterHelper.getHeaderParameters(request, JDBC_HEADERS);

            assertEquals("jdbc:h2:mem:test", parameters[0]);
            assertNull(parameters[1], "user must stay null so DriverManager falls back to URL-embedded credentials");
            assertNull(parameters[2]);
        }
    }

    @Nested
    @DisplayName("x-config header")
    class XConfigHeader {

        @Test
        @DisplayName("decodes dot-separated Base64 values positionally")
        void decodesPositionally() {
            String config = base64("jdbc:postgresql://db.example.com:5432/app")
                    + "." + base64("app_user")
                    + "." + base64("p@ss.word");

            HttpServerRequest request = requestWith("x-config", config);

            assertArrayEquals(
                    new String[] { "jdbc:postgresql://db.example.com:5432/app", "app_user", "p@ss.word" },
                    HttpHeaderParameterHelper.getHeaderParameters(request, JDBC_HEADERS));
        }

        @Test
        @DisplayName("Base64 encoding is what lets values contain the '.' separator")
        void encodedValuesMayContainSeparator() {
            // The raw values contain dots; only the encoded form is split, so they survive.
            String[] values = { "jdbc:oracle:thin:@host.domain.local:1521/ORCL", "u.ser", "pa.ss" };

            HttpServerRequest request = requestWith("x-config",
                    HttpHeaderParameterHelper.encodeBase64Values(values));

            assertArrayEquals(values, HttpHeaderParameterHelper.getHeaderParameters(request, JDBC_HEADERS));
        }

        @Test
        @DisplayName("takes precedence over the individual headers when both are present")
        void takesPrecedenceOverIndividualHeaders() {
            HttpServerRequest request = requestWith(
                    "x-config", base64("jdbc:h2:mem:from-config"),
                    "x-jdbc-url", "jdbc:h2:mem:from-individual-header");

            String[] parameters = HttpHeaderParameterHelper.getHeaderParameters(request, JDBC_HEADERS);

            assertEquals("jdbc:h2:mem:from-config", parameters[0]);
        }

        @Test
        @DisplayName("a short x-config leaves the trailing parameters null")
        void shortConfigLeavesTrailingParametersNull() {
            // URL only - the common case for a database that takes credentials in the URL.
            HttpServerRequest request = requestWith("x-config", base64("jdbc:h2:mem:test"));

            String[] parameters = HttpHeaderParameterHelper.getHeaderParameters(request, JDBC_HEADERS);

            assertEquals("jdbc:h2:mem:test", parameters[0]);
            assertNull(parameters[1]);
            assertNull(parameters[2]);
            assertEquals(JDBC_HEADERS.length, parameters.length, "result is always sized to the requested headers");
        }

        @Test
        @DisplayName("extra x-config values beyond the requested headers are ignored, not an error")
        void extraConfigValuesAreIgnored() {
            String config = base64("jdbc:h2:mem:test") + "." + base64("sa") + "." + base64("pw") + "." + base64("extra");

            HttpServerRequest request = requestWith("x-config", config);

            String[] parameters = HttpHeaderParameterHelper.getHeaderParameters(request, JDBC_HEADERS);

            assertEquals(3, parameters.length);
            assertEquals("pw", parameters[2]);
        }

        @Test
        @DisplayName("malformed Base64 fails loudly instead of yielding a garbage connection string")
        void malformedBase64Throws() {
            HttpServerRequest request = requestWith("x-config", "not!valid!base64");

            assertThrows(IllegalArgumentException.class,
                    () -> HttpHeaderParameterHelper.getHeaderParameters(request, JDBC_HEADERS));
        }
    }

    @Nested
    @DisplayName("encodeBase64Values")
    class EncodeBase64Values {

        @Test
        @DisplayName("round-trips through the decoder")
        void roundTrips() {
            String[] values = { "jdbc:h2:mem:test", "sa", "s3cr3t" };

            HttpServerRequest request = requestWith("x-config", HttpHeaderParameterHelper.encodeBase64Values(values));

            assertArrayEquals(values, HttpHeaderParameterHelper.getHeaderParameters(request, JDBC_HEADERS));
        }

        @Test
        @DisplayName("returns an empty string for null or empty input")
        void emptyInput() {
            assertEquals("", HttpHeaderParameterHelper.encodeBase64Values(null));
            assertEquals("", HttpHeaderParameterHelper.encodeBase64Values(new String[0]));
        }

        @Test
        @DisplayName("trailing empty or null values decode back as null, not as empty strings")
        void trailingEmptyValuesDecodeAsNull() {
            // encode() maps both "" and null to an empty segment, so the encoded form ends in
            // trailing dots - and String.split discards trailing empty segments. The values
            // therefore arrive as null. That happens to be what DriverManager wants (null means
            // "no credential"), but it means "" and null are not distinguishable over x-config.
            String encoded = HttpHeaderParameterHelper.encodeBase64Values(new String[] { "jdbc:h2:mem:test", null, "" });

            HttpServerRequest request = requestWith("x-config", encoded);
            String[] parameters = HttpHeaderParameterHelper.getHeaderParameters(request, JDBC_HEADERS);

            assertEquals("jdbc:h2:mem:test", parameters[0]);
            assertNull(parameters[1]);
            assertNull(parameters[2]);
        }

        @Test
        @DisplayName("an empty value between two populated ones survives as an empty string")
        void interiorEmptyValueSurvives() {
            // Only *trailing* empties are dropped by split, so position is preserved here.
            String encoded = HttpHeaderParameterHelper.encodeBase64Values(new String[] { "jdbc:h2:mem:test", "", "pw" });

            HttpServerRequest request = requestWith("x-config", encoded);
            String[] parameters = HttpHeaderParameterHelper.getHeaderParameters(request, JDBC_HEADERS);

            assertEquals("jdbc:h2:mem:test", parameters[0]);
            assertEquals("", parameters[1]);
            assertEquals("pw", parameters[2]);
        }
    }
}
