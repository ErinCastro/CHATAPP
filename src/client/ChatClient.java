package client;

import java.io.*;
import java.net.*;
import java.util.Locale;

public class ChatClient {
    public static void main(String[] args) throws Exception {
        String host = (args.length > 0) ? args[0] : "127.0.0.1";
        int    port = (args.length > 1) ? Integer.parseInt(args[1]) : 5050;

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setTcpNoDelay(true);

            BufferedReader in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            PrintWriter    out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
            BufferedReader console = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));

            // Reader thread: print all server lines
            Thread reader = new Thread(() -> {
                String line;
                try {
                    while ((line = in.readLine()) != null) {
                        System.out.println(line);
                    }
                } catch (IOException ignored) {}
                System.out.println("Disconnected.");
                System.exit(0);
            });
            reader.setDaemon(true);
            reader.start();

            System.out.println("Connected to " + host + ":" + port);
            System.out.println("Type: LOGIN <username>");
            String line;
            while ((line = console.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // small client-side guardrails
                String cmd = line.split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
                if (line.length() > 600) { System.out.println("ERR line too long"); continue; }
                if ("MSG".equals(cmd) || "DM".equals(cmd)) {
                    String[] parts = line.split("\\s+", 3);
                    if (parts.length < 3) { System.out.println("ERR missing text"); continue; }
                    if (parts[2].length() > 500) { System.out.println("ERR message too long"); continue; }
                }

                out.println(line);
                if ("QUIT".equals(cmd)) break;
            }
        }
    }
}
