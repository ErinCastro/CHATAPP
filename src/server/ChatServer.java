package server;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public class ChatServer {
    private final int port;

    // online clients: username -> session
    private final Map<String, ClientSession> clients = new ConcurrentHashMap<>();

    // user credentials (username -> sha256(password))
    private final Map<String, String> creds = new ConcurrentHashMap<>();
    private final File userFile = new File("users.db");

    // persistence
    private final File generalLog = new File("chat_general.log");
    private final File dmLog      = new File("chat_dm.log");

    // config: require password login always
    private static final boolean REQUIRE_PASSWORD = true;

    public ChatServer(int port) {
        this.port = port;
    }

    // ---------- lifecycle ----------
    public void start() throws IOException {
        try (ServerSocket ss = new ServerSocket(port)) {
            log("Server listening on port " + port);
            while (true) {
                Socket socket = ss.accept();
                socket.setTcpNoDelay(true);
                new Thread(new ClientHandler(socket)).start();
            }
        }
    }

    // ---------- helpers ----------
    private static void log(String s) { System.out.println("[SERVER] " + s); }

    private void broadcast(String from, String text) {
        String line = "MSG " + from + " #general " + text;
        for (ClientSession s : clients.values()) s.send(line);
    }

    private void sendDM(String from, String to, String text) {
        ClientSession tgt = clients.get(to);
        if (tgt != null) {
            log("DM route: " + from + " -> " + to + " : " + text);
            tgt.send("DM " + from + " " + text);
        } else {
            log("DM failed (not online): " + from + " -> " + to);
        }
    }

    private void removeClient(String username) {
        if (username != null) {
            clients.remove(username);
            broadcast("server", username + " left the chat");
            log("Disconnected: " + username);
        }
    }

    // ---------- credentials ----------
    private static String sha256(String s) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void loadUsers() {
        if (!userFile.exists()) return;
        try (var br = new BufferedReader(new FileReader(userFile, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                int i = line.indexOf(':');
                if (i > 0) creds.put(line.substring(0, i), line.substring(i + 1));
            }
            log("Loaded users: " + creds.size());
        } catch (IOException e) {
            log("Could not load users: " + e);
        }
    }

    private synchronized void saveUser(String user, String hash) {
        try (var fw = new FileWriter(userFile, StandardCharsets.UTF_8, true)) {
            fw.write(user + ":" + hash + System.lineSeparator());
            fw.flush();
        } catch (IOException e) {
            log("Could not save user: " + e);
        }
    }

    // ---------- history: append & read ----------
    private synchronized void appendGeneralHistory(String from, String text) {
        try (var fw = new FileWriter(generalLog, StandardCharsets.UTF_8, true)) {
            fw.write(Instant.now().toEpochMilli() + "\t" + esc(from) + "\t" + esc(text) + System.lineSeparator());
        } catch (IOException e) { log("Failed to write general log: " + e); }
    }

    private synchronized void appendDMHistory(String from, String to, String text) {
        try (var fw = new FileWriter(dmLog, StandardCharsets.UTF_8, true)) {
            fw.write(Instant.now().toEpochMilli() + "\t" + esc(from) + "\t" + esc(to) + "\t" + esc(text) + System.lineSeparator());
        } catch (IOException e) { log("Failed to write dm log: " + e); }
    }

    private String esc(String s) {
        // simple escaping for tabs/newlines
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "");
    }

    private String unesc(String s) {
        return s.replace("\\t", "\t").replace("\\n", "\n").replace("\\\\", "\\");
    }

    private List<String[]> readLastGeneral(int n) {
        List<String[]> rows = new ArrayList<>();
        if (!generalLog.exists()) return rows;
        try (var br = new BufferedReader(new FileReader(generalLog, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\t", 3);
                if (parts.length == 3) rows.add(parts);
            }
        } catch (IOException e) { log("Failed to read general log: " + e); }
        int from = Math.max(0, rows.size() - n);
        return rows.subList(from, rows.size());
    }

    private List<String[]> readLastDMFor(String user, int n) {
        // return last n DMs where from==user OR to==user
        List<String[]> rows = new ArrayList<>();
        if (!dmLog.exists()) return rows;
        try (var br = new BufferedReader(new FileReader(dmLog, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\t", 4);
                if (parts.length == 4) {
                    String from = unesc(parts[1]);
                    String to   = unesc(parts[2]);
                    if (from.equals(user) || to.equals(user)) rows.add(parts);
                }
            }
        } catch (IOException e) { log("Failed to read dm log: " + e); }
        int fromIdx = Math.max(0, rows.size() - n);
        return rows.subList(fromIdx, rows.size());
    }

    private List<String[]> readLastDMBetween(String a, String b, int n) {
        List<String[]> rows = new ArrayList<>();
        if (!dmLog.exists()) return rows;
        try (var br = new BufferedReader(new FileReader(dmLog, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\t", 4);
                if (parts.length == 4) {
                    String from = unesc(parts[1]);
                    String to   = unesc(parts[2]);
                    if ((from.equals(a) && to.equals(b)) || (from.equals(b) && to.equals(a))) {
                        rows.add(parts);
                    }
                }
            }
        } catch (IOException e) { log("Failed to read dm log: " + e); }
        int fromIdx = Math.max(0, rows.size() - n);
        return rows.subList(fromIdx, rows.size());
    }

    // ---------- per-connection handler ----------
    private class ClientHandler implements Runnable {
        private final Socket socket;
        private String username = null;
        private PrintWriter out;
        private BufferedReader in;

        ClientHandler(Socket socket) { this.socket = socket; }

        @Override public void run() {
            try (socket) {
                in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

                out.println("OK Welcome. Use: REGISTER <user> <pass>  or  LOGIN <user> <pass>");

                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    if (line.length() > 600) { out.println("ERR line too long"); continue; }

                    String[] parts = line.split("\\s+", 3); // cmd, arg1, rest
                    String cmd = parts[0].toUpperCase(Locale.ROOT);

                    switch (cmd) {
                        case "REGISTER": {
                            // REGISTER <user> <pass>
                            if (parts.length < 3) { out.println("ERR usage: REGISTER <user> <pass>"); break; }
                        
                            String u = parts[1].trim();
                            String p = parts[2].trim();
                        
                            if (!u.matches("[A-Za-z0-9_]{1,20}")) { out.println("ERR invalid username"); break; }
                            if (p.isBlank())                      { out.println("ERR password required"); break; }
                            if (creds.containsKey(u))             { out.println("ERR username exists"); break; }
                        
                            String h = sha256(p);
                            creds.put(u, h);
                            saveUser(u, h);
                            log("REGISTER ok: " + u);
                            out.println("OK registered " + u);
                            break;
                        }
                        

                        case "LOGIN": {
                            // require password
                            if (username != null) { out.println("ERR already logged in"); break; }
                            if (parts.length < 3) { out.println("ERR usage: LOGIN <user> <pass>"); break; }

                            String u = parts[1];
                            String pass = parts[2];

                            if (!u.matches("[A-Za-z0-9_]{1,20}")) { out.println("ERR invalid username"); break; }
                            if (REQUIRE_PASSWORD || !creds.isEmpty()) {
                                String stored = creds.get(u);
                                if (stored == null) { log("LOGIN fail unknown: " + u); out.println("ERR unknown user"); break; }
                                if (!stored.equals(sha256(pass))) { log("LOGIN fail badpass: " + u); out.println("ERR bad password"); break; }
                            }

                            if (clients.containsKey(u)) { out.println("ERR user already online"); break; }

                            username = u;
                            clients.put(username, new ClientSession(username, out));
                            log("LOGIN ok: " + username);
                            out.println("OK logged in as " + username);
                            broadcast("server", username + " joined the chat");

                            // auto history replay
                            out.println("OK history start");
                            for (String[] r : readLastGeneral(50)) {
                                String from = unesc(r[1]);
                                String text = unesc(r[2]);
                                out.println("MSG " + from + " #general " + text);
                            }
                            for (String[] r : readLastDMFor(username, 50)) {
                                String from = unesc(r[1]);
                                String to   = unesc(r[2]);
                                String text = unesc(r[3]);
                                if (to.equals(username)) {
                                    out.println("DM " + from + " " + text);
                                } else if (from.equals(username)) {
                                    out.println("DM " + username + " [to " + to + "] " + text);
                                }
                            }
                            out.println("OK history end");
                            break;
                        }

                        case "MSG": {
                            if (!ensureLogin()) break;
                            if (parts.length < 3) { out.println("ERR usage: MSG #general <text>"); break; }
                            String target = parts[1];
                            if (!"#general".equals(target)) { out.println("ERR only #general is supported"); break; }
                            String text = parts[2];
                            if (text.length() > 500) { out.println("ERR message too long"); break; }
                            appendGeneralHistory(username, text);
                            broadcast(username, text);
                            break;
                        }

                        case "DM": {
                            if (!ensureLogin()) break;
                            if (parts.length < 3) { out.println("ERR usage: DM <user> <text>"); break; }
                            String to   = parts[1].trim();
                            String text = parts[2].trim();
                            if (!clients.containsKey(to)) { out.println("ERR user not online"); break; }
                            if (text.isEmpty())          { out.println("ERR empty message"); break; }
                            if (text.length() > 500)     { out.println("ERR message too long"); break; }

                            appendDMHistory(username, to, text);
                            // deliver and echo
                            sendDM(username, to, text);
                            ClientSession me = clients.get(username);
                            if (me != null) me.send("DM " + username + " [to " + to + "] " + text);

                            out.println("OK dm sent to " + to);
                            break;
                        }

                        case "USERS": {
                            if (!ensureLogin()) break;
                            String list = String.join(",", clients.keySet());
                            out.println("USERS " + list);
                            break;
                        }

                        case "HISTORY": {
                            if (!ensureLogin()) break;
                            if (parts.length < 2) { out.println("ERR usage: HISTORY #general <n> | HISTORY DM <user> <n>"); break; }
                            String rest = parts[1] + (parts.length == 3 ? " " + parts[2] : "");
                            String[] hp = rest.split("\\s+");
                            try {
                                if (hp.length >= 2 && "#general".equalsIgnoreCase(hp[0])) {
                                    int n = Integer.parseInt(hp[1]);
                                    for (String[] r : readLastGeneral(n)) {
                                        out.println("MSG " + unesc(r[1]) + " #general " + unesc(r[2]));
                                    }
                                    out.println("OK history end");
                                } else if (hp.length >= 3 && "DM".equalsIgnoreCase(hp[0])) {
                                    String peer = hp[1];
                                    int n = Integer.parseInt(hp[2]);
                                    for (String[] r : readLastDMBetween(username, peer, n)) {
                                        String from = unesc(r[1]);
                                        String to   = unesc(r[2]);
                                        String text = unesc(r[3]);
                                        if (to.equals(username)) {
                                            out.println("DM " + from + " " + text);
                                        } else if (from.equals(username)) {
                                            out.println("DM " + username + " [to " + peer + "] " + text);
                                        }
                                    }
                                    out.println("OK history end");
                                } else {
                                    out.println("ERR usage: HISTORY #general <n> | HISTORY DM <user> <n>");
                                }
                            } catch (NumberFormatException nfe) {
                                out.println("ERR history count must be a number");
                            }
                            break;
                        }

                        case "QUIT": {
                            out.println("OK bye");
                            return;
                        }

                        default:
                            out.println("ERR unknown command");
                    }
                }
            } catch (IOException ignored) {
            } finally {
                removeClient(username);
            }
        }

        private boolean ensureLogin() {
            if (username == null) { out.println("ERR please LOGIN first"); return false; }
            return true;
        }
    }

    // ---------- session ----------
    private static class ClientSession {
        final String username;
        final PrintWriter out;
        ClientSession(String username, PrintWriter out) { this.username = username; this.out = out; }
        void send(String line) { out.println(line); }
    }

    // ---------- entry point ----------
    public static void main(String[] args) throws Exception {
        int port = 5050;
        if (args.length > 0) port = Integer.parseInt(args[0]);
        ChatServer s = new ChatServer(port);
        s.loadUsers();
        System.out.println("[SERVER] users.db path: " + new File("users.db").getAbsolutePath());
        s.start();
    }
}
