package me.gravolan.proxy;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.security.cert.CertificateException;
import java.util.logging.*;
import javax.net.ssl.*;
import java.security.*;

public class Proxy {
    private static final Logger logger = Logger.getLogger(Proxy.class.getName());
    private static final LimitedCache<String, byte[]> cache = new LimitedCache<>((int) Runtime.getRuntime().freeMemory());
    private static final int PORT = 8000;
    private static final String KEYSTORE_PATH = "src/me/gravolan/proxy/keystore.jks";
    private static final String KEYSTORE_PASSWORD = "pwnword";

    public static void main(String[] args) throws IOException, KeyStoreException, CertificateException, NoSuchAlgorithmException, UnrecoverableKeyException, KeyManagementException {
        logger.setLevel(Level.INFO);
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setLevel(Level.ALL);
        logger.addHandler(consoleHandler);

        // Load the keystore
        char[] keystorePasswordArray = KEYSTORE_PASSWORD.toCharArray();
        KeyStore keystore = KeyStore.getInstance("JKS");
        keystore.load(new FileInputStream(KEYSTORE_PATH), keystorePasswordArray);

        // Create the SSL context
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keystore, keystorePasswordArray);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keystore);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        HttpsServer server = HttpsServer.create(new InetSocketAddress(PORT), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext));
        server.createContext("/", new ProxyHandler());
        server.setExecutor(null);
        server.start();
        logger.info("Proxy server started on port 8000");
    }

    static class ProxyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String requestURL = exchange.getRequestURI().toString().substring(1);
            logger.info("Received a new request: " + requestURL);

            // Check if the requested URL is in the cache
            byte[] cachedResponse = cache.get(requestURL);
            if (cachedResponse != null) {
                // Serve the response from the cache
                System.out.println("Serving response from cache");
                exchange.sendResponseHeaders(200, cachedResponse.length);
                OutputStream outputStream = exchange.getResponseBody();
                outputStream.write(cachedResponse);
                outputStream.close();
                return;
            }

            URL url;
            try {
                url = new URI(requestURL).toURL();
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(exchange.getRequestMethod());

            // Copy request headers from the client to the server
            for (String header : exchange.getRequestHeaders().keySet()) {
                connection.setRequestProperty(header, exchange.getRequestHeaders().getFirst(header));
            }
            logger.info("Copied request headers from client to server");

            try (AutoCloseable ignored = connection::disconnect) { // Wrap the connection in a try-with-resources block (Java 7+)
                connection.setDoInput(true);
                connection.setDoOutput(true);
                logger.info("Enabled connection input and output");

                // Read the server response
                int responseCode = connection.getResponseCode();
                logger.info("Received server response code: " + responseCode);
                if(responseCode>=400) {
                    StringBuilder htmlBuilder = new StringBuilder();
                    htmlBuilder.append("<html>")
                            .append("<body>")
                            .append("<p>")
                            .append("Error: ")
                            .append(responseCode)
                            .append("</p>")
                            .append("</body>")
                            .append("</html>");
                    exchange.sendResponseHeaders(200, htmlBuilder.length());
                    OutputStream outputStream = exchange.getResponseBody();
                    outputStream.write(htmlBuilder.toString().getBytes());
                    outputStream.flush();
                    outputStream.close();
                    return;
                }
                InputStream inputStream = connection.getInputStream();
                ByteArrayOutputStream responseBytes = new ByteArrayOutputStream();
                logger.info("Read server response");

                // Copy response headers from the server to the client
                logger.info("Headers to copy: " + connection.getHeaderFields().keySet().size());
                for (String header : connection.getHeaderFields().keySet()) {
                    if (header != null) {
                        exchange.getResponseHeaders().put(header, connection.getHeaderFields().get(header));
                    }
                    else logger.info("Header is null");
                }
                logger.info("Copied response headers from server to client");

                exchange.sendResponseHeaders(responseCode, connection.getContentLengthLong());
                OutputStream outputStream = exchange.getResponseBody();

                // Copy response body from the server to the client
                byte[] buffer = new byte[4096];
                int bytesRead;
                logger.info("Buffer created");
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    exchange.getResponseBody().write(buffer, 0, bytesRead);
                }
                logger.info("Copied response body from server to client");

                if (responseCode == 200) {
                    byte[] responseByteArray = responseBytes.toByteArray();
                    cache.put(requestURL, responseByteArray);
                }

                outputStream.close();
                inputStream.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                connection.disconnect();
            }
            logger.info("Request handled successfully");
        }
    }
}