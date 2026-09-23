package top.mayiqin.url_shortener.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class ClientIpResolverTest {
    private String resolve(String trusted, String peer, String header) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        if (header != null) request.addHeader("X-Forwarded-For", header);
        return new ClientIpResolver(trusted).resolve(request);
    }

    @Test void untrustedPeerCannotSpoofIp() {
        assertEquals("203.0.113.5", resolve("", "203.0.113.5", "1.2.3.4"));
    }
    @Test void walksFromRightAndIgnoresForgedPrefix() {
        assertEquals("203.0.113.5", resolve("10.0.0.0/8", "10.0.0.1", "1.2.3.4,203.0.113.5,10.1.1.1"));
    }
    @Test void invalidHopFallsBackToPeerWithoutDns() {
        assertEquals("10.0.0.1", resolve("10.0.0.0/8", "10.0.0.1", "example.com"));
        assertEquals("10.0.0.1", resolve("10.0.0.0/8", "10.0.0.1", "999.1.2.3"));
        assertEquals("10.0.0.1", resolve("10.0.0.0/8", "10.0.0.1", "1.2.3.4,"));
    }
    @Test void supportsIpv6ProxyChains() {
        assertEquals("203.0.113.5", resolve("2001:db8::/32", "2001:db8::1", "203.0.113.5,2001:db8::2"));
    }
    @Test void invalidConfigurationFailsEarly() {
        assertThrows(IllegalArgumentException.class, () -> new ClientIpResolver("example.com/8"));
        assertThrows(IllegalArgumentException.class, () -> new ClientIpResolver("127.0.0.1/33"));
    }
}
