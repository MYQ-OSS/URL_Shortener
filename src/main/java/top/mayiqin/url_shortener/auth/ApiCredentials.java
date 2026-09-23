package top.mayiqin.url_shortener.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "shortlink.auth")
public class ApiCredentials {
    private Map<String, String> tokens = new LinkedHashMap<>();

    public Map<String, String> getTokens() { return tokens; }
    public void setTokens(Map<String, String> tokens) { this.tokens = tokens; }
}
