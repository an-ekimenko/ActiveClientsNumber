package acn.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import javax.net.ssl.SSLContext;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import acn.config.ACNApiConfig;
import my.CertUpdater;
import my.KeyStoreManager;

@ExtendWith(MockitoExtension.class)
class ACN_HttpClientTest {

	private static final String TARGET_URL = "https://example.com/data";
	private static final String TOKEN_URL = "https://example.com/token";




    @Mock
    private HttpClientBuilder httpClientBuilder;

    @Mock
    private CloseableHttpClient mockHttpClient;

    private ACNApiConfig config;
    private ACN_HttpClient acnHttpClient;

    @BeforeEach
    void setUp() {
        config = new ACNApiConfig();
        config.setDataEndpoint("https://example.com");

        lenient().when(httpClientBuilder.setConnectionManager(any())).thenReturn(httpClientBuilder);
        lenient().when(httpClientBuilder.setDefaultRequestConfig(any())).thenReturn(httpClientBuilder);
        lenient().when(httpClientBuilder.build()).thenReturn(mockHttpClient);

        acnHttpClient = new ACN_HttpClient(config, httpClientBuilder);
    }

    @Test
    @DisplayName("Basic Auth: should encode username:password into Basic Authorization header")
    void fetchRawJson_BasicAuth_SetsBasicHeader() throws Exception {
        config.setAuthType("basic");
        ACNApiConfig.AuthConfig auth = new ACNApiConfig.AuthConfig();
        ACNApiConfig.BasicAuthConfig basic = new ACNApiConfig.BasicAuthConfig();
        basic.setUsername("acn_user");
        basic.setPassword("acn_pass");
        auth.setBasic(basic);
        config.setAuth(auth);

        stubSingleResponse(200, "{\"dateString\":\"202610\",\"acliveClients\":[]}");

        String result = executeWithMockedSecurity(() -> acnHttpClient.fetchRawJson(TARGET_URL));

        assertEquals("{\"dateString\":\"202610\",\"acliveClients\":[]}", result);
        ArgumentCaptor<ClassicHttpRequest> requestCaptor = ArgumentCaptor.forClass(ClassicHttpRequest.class);
        verify(mockHttpClient).execute(requestCaptor.capture(), any(HttpClientResponseHandler.class));

        ClassicHttpRequest captured = requestCaptor.getValue();
        assertEquals(TARGET_URL, captured.getUri().toString());
        Header authHeader = captured.getFirstHeader("Authorization");
        assertNotNull(authHeader);
        String expectedEncoded = Base64.getEncoder().encodeToString("acn_user:acn_pass".getBytes(StandardCharsets.UTF_8));
        assertEquals("Basic " + expectedEncoded, authHeader.getValue());
    }

    @Test
    @DisplayName("Static Token Auth: should attach Bearer header with configured static_token")
    void fetchRawJson_StaticTokenAuth_SetsBearerHeader() throws Exception {
        config.setAuthType("token");
        ACNApiConfig.AuthConfig auth = new ACNApiConfig.AuthConfig();
        auth.setStaticToken("my-acn-static-token");
        config.setAuth(auth);

        stubSingleResponse(200, "{\"dateString\":\"202610\",\"acliveClients\":[]}");

        String result = executeWithMockedSecurity(() -> acnHttpClient.fetchRawJson(TARGET_URL));

        assertEquals("{\"dateString\":\"202610\",\"acliveClients\":[]}", result);
        ArgumentCaptor<ClassicHttpRequest> requestCaptor = ArgumentCaptor.forClass(ClassicHttpRequest.class);
        verify(mockHttpClient).execute(requestCaptor.capture(), any(HttpClientResponseHandler.class));

        Header authHeader = requestCaptor.getValue().getFirstHeader("Authorization");
        assertNotNull(authHeader);
        assertEquals("Bearer my-acn-static-token", authHeader.getValue());
    }

    @Test
    @DisplayName("OAuth2 Client Credentials: should acquire token, cache it, and send Bearer header")
    void fetchRawJson_OAuth2Auth_AcquiresTokenCachesAndSendsBearerHeader() throws Exception {
        config.setAuthType("oauth2");
        ACNApiConfig.AuthConfig auth = new ACNApiConfig.AuthConfig();
        ACNApiConfig.OAuth2Config oauth2 = new ACNApiConfig.OAuth2Config();
        oauth2.setTokenEndpoint(TOKEN_URL);
        oauth2.setClientId("service-rcp2");
        oauth2.setClientSecret("secret-key");
        oauth2.setScope("rcp2.api.public");
        auth.setOauth2(oauth2);
        config.setAuth(auth);

        when(mockHttpClient.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class)))
                .thenAnswer(invocation -> {
                    ClassicHttpRequest req = invocation.getArgument(0);
                    HttpClientResponseHandler<String> handler = invocation.getArgument(1);

                    ClassicHttpResponse mockResp = mock(ClassicHttpResponse.class);
                    when(mockResp.getCode()).thenReturn(200);

                    if ("POST".equalsIgnoreCase(req.getMethod())) {
                        String tokenJson = "{\"access_token\":\"dynamic-acn-oauth2-token\",\"expires_in\":3600}";
                        when(mockResp.getEntity()).thenReturn(new StringEntity(tokenJson, StandardCharsets.UTF_8));
                    } else {
                        String payload = "{\"dateString\":\"202610\",\"acliveClients\":[]}";
                        when(mockResp.getEntity()).thenReturn(new StringEntity(payload, StandardCharsets.UTF_8));
                    }
                    return handler.handleResponse(mockResp);
                });

        String firstResult = executeWithMockedSecurity(() -> acnHttpClient.fetchRawJson(TARGET_URL));
        String secondResult = executeWithMockedSecurity(() -> acnHttpClient.fetchRawJson(TARGET_URL));

        assertEquals("{\"dateString\":\"202610\",\"acliveClients\":[]}", firstResult);
        assertEquals("{\"dateString\":\"202610\",\"acliveClients\":[]}", secondResult);

        ArgumentCaptor<ClassicHttpRequest> requestCaptor = ArgumentCaptor.forClass(ClassicHttpRequest.class);
        verify(mockHttpClient, times(3)).execute(requestCaptor.capture(), any(HttpClientResponseHandler.class));

        List<ClassicHttpRequest> capturedRequests = requestCaptor.getAllValues();
        assertEquals("POST", capturedRequests.get(0).getMethod());
        assertEquals(TOKEN_URL, capturedRequests.get(0).getUri().toString());

        assertEquals("GET", capturedRequests.get(1).getMethod());
        assertEquals("Bearer dynamic-acn-oauth2-token", capturedRequests.get(1).getFirstHeader("Authorization").getValue());

        assertEquals("GET", capturedRequests.get(2).getMethod());
        assertEquals("Bearer dynamic-acn-oauth2-token", capturedRequests.get(2).getFirstHeader("Authorization").getValue());
    }

    @Test
    @DisplayName("None Auth: should omit Authorization header completely")
    void fetchRawJson_NoneAuth_OmitsAuthorizationHeader() throws Exception {
        config.setAuthType("none");
        stubSingleResponse(200, "{}");

        executeWithMockedSecurity(() -> acnHttpClient.fetchRawJson(TARGET_URL));

        ArgumentCaptor<ClassicHttpRequest> requestCaptor = ArgumentCaptor.forClass(ClassicHttpRequest.class);
        verify(mockHttpClient).execute(requestCaptor.capture(), any(HttpClientResponseHandler.class));
        assertNull(requestCaptor.getValue().getFirstHeader("Authorization"));
    }

    @Test
    @DisplayName("TLS Lifecycle: should invoke CertUpdater for both token_endpoint and target URL on OAuth2")
    void fetchRawJson_OAuth2_TriggersCertUpdateForBothEndpoints() throws Exception {
        config.setAuthType("oauth2");
        ACNApiConfig.AuthConfig auth = new ACNApiConfig.AuthConfig();
        ACNApiConfig.OAuth2Config oauth2 = new ACNApiConfig.OAuth2Config();
        oauth2.setTokenEndpoint(TOKEN_URL);
        oauth2.setClientId("service-rcp2");
        oauth2.setClientSecret("secret-key");
        auth.setOauth2(oauth2);
        config.setAuth(auth);

        when(mockHttpClient.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class)))
                .thenAnswer(invocation -> {
                    ClassicHttpRequest req = invocation.getArgument(0);
                    HttpClientResponseHandler<String> handler = invocation.getArgument(1);
                    ClassicHttpResponse mockResp = mock(ClassicHttpResponse.class);
                    when(mockResp.getCode()).thenReturn(200);
                    String body = "POST".equalsIgnoreCase(req.getMethod())
                            ? "{\"access_token\":\"tok\",\"expires_in\":3600}"
                            : "{}";
                    when(mockResp.getEntity()).thenReturn(new StringEntity(body, StandardCharsets.UTF_8));
                    return handler.handleResponse(mockResp);
        });
        
        try (MockedStatic ksMock = mockStatic(KeyStoreManager.class);
                MockedStatic certMock = mockStatic(CertUpdater.class)) {
                	ksMock.when(KeyStoreManager::getSSLContext).thenReturn(SSLContext.getDefault());
                	acnHttpClient.fetchRawJson(TARGET_URL);
                	certMock.verify(() -> CertUpdater.updateWithoutAuth(TOKEN_URL));
                	certMock.verify(() -> CertUpdater.updateWithoutAuth(TARGET_URL));
                }
    }
    
    @Test
    @DisplayName("Validation: should throw IllegalStateException when dynamicTargetUrl is missing or blank")
    void fetchRawJson_MissingTargetUrl_ThrowsIllegalStateException() {
    	config.setAuthType("none");
        assertThrows(IllegalStateException.class, () -> acnHttpClient.fetchRawJson("   "));
        verifyNoInteractions(mockHttpClient);
    }
    
    @Test
    @DisplayName("Validation: should throw IllegalStateException when auth_type is unsupported")
    void fetchRawJson_InvalidAuthType_ThrowsIllegalStateException() {
    	config.setAuthType("kerberos");
        assertThrows(IllegalStateException.class, () ->
        	executeWithMockedSecurity(() -> acnHttpClient.fetchRawJson(TARGET_URL))
        );
    }
    
    @Test
    @DisplayName("Error Handling: should throw RuntimeException when server returns HTTP 500")
    void fetchRawJson_ServerErrorStatus_ThrowsRuntimeException() throws Exception {
    	config.setAuthType("none");
        when(mockHttpClient.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class)))
                .thenAnswer(invocation -> {
                	HttpClientResponseHandler handler = invocation.getArgument(1);
                	ClassicHttpResponse mockResp = mock(ClassicHttpResponse.class);
                	when(mockResp.getCode()).thenReturn(500);
                	when(mockResp.getReasonPhrase()).thenReturn("Internal Server Error");
                	return handler.handleResponse(mockResp);
                });
        
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
        	executeWithMockedSecurity(() -> acnHttpClient.fetchRawJson(TARGET_URL))
        );
        
        assertTrue(ex.getMessage().contains("500"));
    }
    
    @Test
    @DisplayName("Error Handling: should throw RuntimeException when HTTP 200 has null entity")
    void fetchRawJson_EmptyBodyOn200_ThrowsRuntimeException() throws Exception {
    	config.setAuthType("none");
        stubSingleResponse(200, null);
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
        	executeWithMockedSecurity(() -> acnHttpClient.fetchRawJson(TARGET_URL))
        );
        assertTrue(ex.getMessage().contains("empty body"));
    }
    
    // --- Helper methods ---
    private void stubSingleResponse(int statusCode, String responseBody) throws Exception {
    	when(mockHttpClient.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class)))
                .thenAnswer(invocation -> {
                	HttpClientResponseHandler handler = invocation.getArgument(1);
                	ClassicHttpResponse mockResp = mock(ClassicHttpResponse.class);
                	when(mockResp.getCode()).thenReturn(statusCode);
                	if (responseBody != null) {
                		when(mockResp.getEntity()).thenReturn(new StringEntity(responseBody, StandardCharsets.UTF_8));
                	}
                	return handler.handleResponse(mockResp);
                });
    }
    
    private <T> T executeWithMockedSecurity(ThrowingSupplier<T> action) throws Exception {
        try (MockedStatic<KeyStoreManager> ksMock = mockStatic(KeyStoreManager.class);
             MockedStatic<CertUpdater> certMock = mockStatic(CertUpdater.class)) {

            ksMock.when(KeyStoreManager::getSSLContext).thenReturn(SSLContext.getDefault());
            return action.get();
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

}
