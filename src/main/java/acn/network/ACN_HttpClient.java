package acn.network;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.net.ssl.SSLContext;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.util.Timeout;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import acn.config.ACNApiConfig;
import my.CertUpdater;
import my.KeyStoreManager;

/**
 * High-performance HTTP client wrapper dedicated to pulling Active Clients Number (ACN) data streams.
 * Supports none, basic, static token, and OAuth2 Client Credentials authentication with token caching.
 */
@Singleton
public class ACN_HttpClient implements AutoCloseable {

    private static final Logger logger = LogManager.getLogger(ACN_HttpClient.class);

    private final ACNApiConfig config;
    private final HttpClientBuilder httpClientBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile CloseableHttpClient httpClient;

    // Cache fields for the dynamic OAuth2 access token
    private String cachedOAuth2Token;
    private Instant tokenExpiresAt = Instant.EPOCH;

    @Inject
    public ACN_HttpClient(ACNApiConfig config, HttpClientBuilder httpClientBuilder) {
        this.config = config;
        this.httpClientBuilder = httpClientBuilder;
    }

    /**
     * Dynamically generates the HTTP Authorization header based on configuration profiles.
     */
    private String buildAuthHeader() throws Exception {
        String rawAuthType = config.getAuthType();
        String authType = (rawAuthType != null) ? rawAuthType.trim().toLowerCase() : "";
        ACNApiConfig.AuthConfig auth = config.getAuth();

        try {
            return switch (authType) {
                case "basic" -> {
                    if (auth == null || auth.getBasic() == null
                            || auth.getBasic().getUsername() == null
                            || auth.getBasic().getPassword() == null) {
                        throw new IllegalStateException("Basic auth credentials (username/password) are missing in configuration.");
                    }
                    String authString = auth.getBasic().getUsername() + ":" + auth.getBasic().getPassword();
                    yield "Basic " + Base64.getEncoder().encodeToString(authString.getBytes(StandardCharsets.UTF_8));
                }
                case "token" -> {
                    if (auth == null || auth.getStaticToken() == null || auth.getStaticToken().trim().isEmpty()) {
                        throw new IllegalStateException("Static Bearer token is missing in configuration.");
                    }
                    yield "Bearer " + auth.getStaticToken().trim();
                }
                case "oauth2" -> {
                    if (auth == null || auth.getOauth2() == null) {
                        throw new IllegalStateException("OAuth2 configuration block is missing.");
                    }
                    yield "Bearer " + getOrRefreshOAuth2Token(auth.getOauth2());
                }
                case "none" -> {
                    logger.debug("Executing requests without authentication headers (auth_type: none)");
                    yield null;
                }
                default -> throw new IllegalStateException(
                        authType.isEmpty()
                                ? "Authentication type (auth_type) is missing or empty in configuration."
                                : "Unsupported authentication type detected: '" + rawAuthType + "'"
                );
            };
        } catch (IllegalStateException e) {
            logger.error("Authorization header build failed: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Returns a valid cached OAuth2 access token or requests a new one from the token endpoint.
     */
    private synchronized String getOrRefreshOAuth2Token(ACNApiConfig.OAuth2Config oauth2Config) throws Exception {
        if (cachedOAuth2Token != null && Instant.now().isBefore(tokenExpiresAt.minusSeconds(30))) {
            return cachedOAuth2Token;
        }

        String tokenEndpoint = oauth2Config.getTokenEndpoint();
        String clientId = oauth2Config.getClientId();
        String clientSecret = oauth2Config.getClientSecret();
        String scope = oauth2Config.getScope();

        if (tokenEndpoint == null || clientId == null || clientSecret == null) {
            throw new IllegalStateException("OAuth2 credentials (token_endpoint, client_id, client_secret) are incomplete.");
        }

        logger.info("Requesting new OAuth2 access token from: {}", tokenEndpoint);

        List<NameValuePair> formParams = new ArrayList<>();
        formParams.add(new BasicNameValuePair("grant_type", "client_credentials"));
        formParams.add(new BasicNameValuePair("client_id", clientId));
        formParams.add(new BasicNameValuePair("client_secret", clientSecret));
        if (scope != null && !scope.trim().isEmpty()) {
            formParams.add(new BasicNameValuePair("scope", scope.trim()));
        }

        ClassicHttpRequest tokenRequest = ClassicRequestBuilder.post(tokenEndpoint)
                .setHeader("Accept", "application/json")
                .setEntity(new UrlEncodedFormEntity(formParams, StandardCharsets.UTF_8))
                .build();

        return httpClient.execute(tokenRequest, response -> {
            int status = response.getCode();
            String body = response.getEntity() != null
                    ? EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8)
                    : "";

            if (status >= 200 && status < 300) {
                JsonNode jsonNode = objectMapper.readTree(body);
                JsonNode tokenNode = jsonNode.get("access_token");

                if (tokenNode == null || tokenNode.asText().isEmpty()) {
                    throw new RuntimeException("OAuth2 response did not contain 'access_token'. Body: " + body);
                }

                this.cachedOAuth2Token = tokenNode.asText();
                long expiresIn = jsonNode.has("expires_in") ? jsonNode.get("expires_in").asLong(3600) : 3600;
                this.tokenExpiresAt = Instant.now().plusSeconds(expiresIn);

                logger.info("OAuth2 access token successfully acquired (expires in {}s).", expiresIn);
                return this.cachedOAuth2Token;
            } else {
                throw new RuntimeException("Failed to obtain OAuth2 token. HTTP status: " + status + ", Body: " + body);
            }
        });
    }

    /**
     * Executes an HTTP GET request to fetch the raw JSON payload from the dynamic target URL.
     */
    public String fetchRawJson(String dynamicTargetUrl) throws Exception {
        if (dynamicTargetUrl == null || dynamicTargetUrl.trim().isEmpty()) {
            throw new IllegalStateException("Target dynamicTargetUrl URI is missing or empty.");
        }

        if (this.httpClient == null) {
            synchronized (this) {
                if (this.httpClient == null) {
                    triggerCertUpdate(dynamicTargetUrl);

                    ACNApiConfig.ConnectionConfig connCfg = (config.getConnection() != null)
                            ? config.getConnection()
                            : new ACNApiConfig.ConnectionConfig();

                    Timeout connectTimeout = Timeout.ofMilliseconds(connCfg.getConnectTimeoutMs());
                    Timeout responseTimeout = Timeout.ofMilliseconds(connCfg.getResponseTimeoutMs());

                    ConnectionConfig connectionConfig = ConnectionConfig.custom()
                            .setConnectTimeout(connectTimeout)
                            .build();

                    SSLContext sslContext = KeyStoreManager.getSSLContext();
                    SSLConnectionSocketFactory sslSocketFactory = new SSLConnectionSocketFactory(sslContext);

                    PoolingHttpClientConnectionManager connManager = PoolingHttpClientConnectionManagerBuilder.create()
                            .setSSLSocketFactory(sslSocketFactory)
                            .setDefaultConnectionConfig(connectionConfig)
                            .build();

                    RequestConfig defaultRequestConfig = RequestConfig.custom()
                            .setResponseTimeout(responseTimeout)
                            .build();

                    this.httpClient = httpClientBuilder
                            .setConnectionManager(connManager)
                            .setDefaultRequestConfig(defaultRequestConfig)
                            .build();

                    logger.info("ACN HTTP client initialized (connectTimeout={}ms, responseTimeout={}ms).",
                            connCfg.getConnectTimeoutMs(), connCfg.getResponseTimeoutMs());
                }
            }
        }

        String authHeader = buildAuthHeader();
        ClassicHttpRequest request = ClassicRequestBuilder.get(dynamicTargetUrl)
        		.setHeader("Accept", "application/json")
        		.build();
       if (authHeader != null) 
       		request.setHeader("Authorization", authHeader);

       logger.info("Executing ACN API request to: {}", dynamicTargetUrl);
	   return httpClient.execute(request, response -> {
       		int status = response.getCode();
       		logger.info("ACN HTTP response status code received: {}", status);
       		if (status >= 200 && status < 300) {
       			if (response.getEntity() == null) 
       				throw new RuntimeException("The server returned a successful status code "
       						+ status + " but an empty body.");
        		
        		return EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
        	} else 
        		throw new RuntimeException("The server returned an error status code: " + status
        				+ ". Reason: " + response.getReasonPhrase());
  	   });
    }
    
    /**
     * Evaluates active configuration criteria to run specific TLS certificate lifecycle updates.
     */
    private void triggerCertUpdate(String urlString) {
    	String rawAuthType = config.getAuthType();
        if (rawAuthType == null) 
        	return;

        String authType = rawAuthType.trim().toLowerCase();
        ACNApiConfig.AuthConfig auth = config.getAuth();
        if ("basic".equals(authType) && auth != null && auth.getBasic() != null) {
        	String username = auth.getBasic().getUsername();
        	String password = auth.getBasic().getPassword();
        	if (username != null && password != null) {
        		CertUpdater.updateByBasicAuth(urlString, username, password);
        	}
        } else if ("token".equals(authType) && auth != null && auth.getStaticToken() != null) {
        	CertUpdater.updateByToken(urlString, auth.getStaticToken());
        } else if ("oauth2".equals(authType) && auth != null && auth.getOauth2() != null) {
        	String tokenEndpoint = auth.getOauth2().getTokenEndpoint();
        	if (tokenEndpoint != null && !tokenEndpoint.trim().isEmpty()) {
        		CertUpdater.updateWithoutAuth(tokenEndpoint);
        	}
        	CertUpdater.updateWithoutAuth(urlString);
        } else if ("none".equals(authType)) {
        	CertUpdater.updateWithoutAuth(urlString);
        }
    }
    
    @Override
    public void close() throws IOException {
    	if (this.httpClient != null) {
        	logger.info("Shutting down ACN HTTP transport infrastructure...");
        	this.httpClient.close();
        }
    }
}