package club.bigtian.mf.plugin.core.service;

import com.intellij.util.net.NetUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;

public class LoginService implements HttpHandler {
    private static HttpServer httpServer;

    public static LoginService getInstance() {
        return new LoginService();
    }


    public int getAvailableSocketPort() {
        try {
            int port = NetUtils.findAvailableSocketPort();
            System.out.println(port);

            return port;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        System.out.println(new String(bytes));
        exchange.sendResponseHeaders(200, 0);
        exchange.close();

    }

    public void startServer() {
        try {
            this.httpServer = HttpServer.create(new InetSocketAddress(getAvailableSocketPort()), 10);
            this.httpServer.createContext("/auth", this);
            this.httpServer.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void stopServer() {
        this.httpServer.stop(0);
    }
}
