package server;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ChatServer with:
 *  - LOGIN / REGISTER (same style as your previous file)
 *  - MSG #general <text>
 *  - DM <user> <text>
 *  - USERS
 *  - QUIT
 *  - ATTACH / DATA / ATTACH_END (file relay; General + DM)
 *
 * Protocol additions (Client -> Server):
 *   ATTACH #general <filename> <size>
 *   ATTACH <user> <filename> <size>
 *   DATA <base64>
 *   ATTACH_END
 *
 * Server -> Client during relay:
 *   FILE <id> <from> #general <filename> <size>
 *   FILE <id> <from> [to <peer>] <filename> <size>   // echo to sender (DM)
 *   FILE <id> <from> <filename> <size>               // to DM recipient
 *   FILE_DATA <id> <base64>
 *   FILE_END <id>
 */
public class ChatServer {
    private final int port;

    // Online sessions
    private final Map<String, ClientSession> clients = new ConcurrentHashMap<>();

    // User credentials (username -> password-hash or token). Stays compatible with your file.
    private final Map<String, String> creds = new ConcurrentHashMap<>();
    private final File userFile = new File("users.db");

    // For FILE/attachment ids
    private final AtomicLong nextId = new AtomicLong(1);

    // Limits
    private static final int MAX_LINE  = 8192;   // allow big DATA lines
    private static final int MAX_TEXT  = 500;
    private static final String USER_RE = "[A-Za-z0-9_]{1,20}";

    public ChatServer(int port) { this.port = port; }

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
        String line = "MSG " + nextId.getAndIncrement() + " " + from + " #general " + text;
        for (ClientSession s : clients.values()) s.send(line);
    }

    private void sendDM(String from, String to, String text) {
    long id = nextId.getAndIncrement();
    ClientSession tgt = clients.get(to);
    if (tgt != null) {
        tgt.send("DM " + id + " " + from + " " + text);
    }
}


    private void removeClient(String username) {
        if (username != null) {
            clients.remove(username);
            broadcast("server", username + " left the chat");
            log("Disconnected: " + username);
        }
    }

    // ---------- credentials (simple, same style you used) ----------
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
        } catch (IOException e) { log("Could not load users: " + e); }
    }

    private synchronized void saveUser(String user, String hash) {
        try (var fw = new FileWriter(userFile, StandardCharsets.UTF_8, true)) {
            fw.write(user + ":" + hash + System.lineSeparator());
            fw.flush();
        } catch (IOException e) { log("Could not save user: " + e); }
    }

    // ---------- relay helpers for attachments ----------
    private void relayFileGeneral(String from, String filename, long size, List<String> chunks) {
        long id = nextId.getAndIncrement();
        String head = "FILE " + id + " " + from + " #general " + filename + " " + size;
        for (ClientSession s : clients.values()) {
            s.send(head);
            for (String c : chunks) s.send("FILE_DATA " + id + " " + c);
            s.send("FILE_END " + id);
        }
    }

    private void relayFileDm(String from, String to, String filename, long size, List<String> chunks) {
        long id = nextId.getAndIncrement();
        ClientSession tgt = clients.get(to);
        ClientSession me  = clients.get(from);
        if (tgt != null) {
            tgt.send("FILE " + id + " " + from + " " + filename + " " + size);
            for (String c : chunks) tgt.send("FILE_DATA " + id + " " + c);
            tgt.send("FILE_END " + id);
        }
        if (me != null) {
            me.send("FILE " + id + " " + from + " [to " + to + "] " + filename + " " + size);
            for (String c : chunks) me.send("FILE_DATA " + id + " " + c);
            me.send("FILE_END " + id);
        }
    }

    // ---------- per-connection handler ----------
    private class ClientHandler implements Runnable {
        private final Socket socket;
        private String username = null;
        private PrintWriter out;
        private BufferedReader in;

        // attachment upload state (per-connection)
        private boolean uploading = false;
        private boolean uploadIsGeneral = false;
        private String  uploadPeer = null;
        private String  uploadFile = null;
        private long    uploadSize = 0;
        private final List<String> uploadChunks = new ArrayList<>();

        ClientHandler(Socket socket) { this.socket = socket; }

        @Override public void run() {
            try (socket) {
                in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

                out.println("OK Welcome. Use: REGISTER <user> <pass>  or  LOGIN <user> [pass]");

                String line;
                while ((line = in.readLine()) != null) {
                    if (line.length() > MAX_LINE) { out.println("ERR line too long"); continue; }
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    // If currently in upload mode, only allow DATA / ATTACH_END
                    if (uploading) {
                        if (line.startsWith("DATA ")) {
                            String base64 = line.substring(5).trim();
                            if (!base64.isEmpty()) uploadChunks.add(base64);
                            else out.println("ERR DATA requires payload");
                            continue;
                        } else if ("ATTACH_END".equalsIgnoreCase(line)) {
                            if (uploadIsGeneral) relayFileGeneral(username, uploadFile, uploadSize, uploadChunks);
                            else                 relayFileDm(username, uploadPeer, uploadFile, uploadSize, uploadChunks);
                            out.println("OK file sent");
                            // reset upload state
                            uploading = false;
                            uploadIsGeneral = false;
                            uploadPeer = null;
                            uploadFile = null;
                            uploadSize = 0;
                            uploadChunks.clear();
                            continue;
                        } else {
                            out.println("ERR currently uploading; send DATA <base64> or ATTACH_END");
                            continue;
                        }
                    }

                    String[] parts = line.split("\\s+", 3); // cmd, arg1, rest
                    String cmd = parts[0].toUpperCase(Locale.ROOT);

                    switch (cmd) {
                        case "REGISTER": {
                            if (parts.length < 3) { out.println("ERR usage: REGISTER <user> <pass>"); break; }
                            String u = parts[1].trim();
                            String p = parts[2].trim();
                            if (!u.matches(USER_RE)) { out.println("ERR invalid username"); break; }
                            if (p.isBlank())         { out.println("ERR password required"); break; }
                            if (creds.containsKey(u)){ out.println("ERR username exists"); break; }
                            String h = sha256(p); // simple hash (keep consistent with your earlier file)
                            creds.put(u, h);
                            saveUser(u, h);
                            out.println("OK registered " + u);
                            break;
                        }

                        case "LOGIN": {
                            if (username != null) { out.println("ERR already logged in"); break; }
                            if (parts.length < 2) { out.println("ERR usage: LOGIN <user> [pass]"); break; }
                            String u = parts[1];
                            String pass = (parts.length >= 3) ? parts[2] : null;
                            if (!u.matches(USER_RE)) { out.println("ERR invalid username"); break; }

                            boolean credentialedMode = !creds.isEmpty();
                            if (!credentialedMode) {
                                if (clients.containsKey(u)) { out.println("ERR username taken"); break; }
                                username = u;
                                clients.put(username, new ClientSession(username, out));
                                out.println("OK logged in as " + username);
                                broadcast("server", username + " joined the chat");
                                break;
                            }
                            String stored = creds.get(u);
                            if (stored == null) { out.println("ERR unknown user"); break; }
                            if (pass == null || !stored.equals(sha256(pass))) { out.println("ERR bad password"); break; }
                            if (clients.containsKey(u)) { out.println("ERR user already online"); break; }

                            username = u;
                            clients.put(username, new ClientSession(username, out));
                            out.println("OK logged in as " + username);
                            broadcast("server", username + " joined the chat");
                            break;
                        }

                        case "MSG": {
                            if (!ensureLogin()) break;
                            if (parts.length < 3) { out.println("ERR usage: MSG #general <text>"); break; }
                            if (!"#general".equals(parts[1])) { out.println("ERR only #general is supported"); break; }
                            String text = parts[2];
                            if (text.length() > MAX_TEXT) { out.println("ERR message too long"); break; }
                            broadcast(username, text);
                            break;
                        }

                        case "DM": {
                            if (!ensureLogin()) break;
                            if (parts.length < 3) { out.println("ERR usage: DM <user> <text>"); break; }
                            String[] p2 = parts[2].split("\\s+", 2);
                            if (p2.length < 2) { out.println("ERR usage: DM <user> <text>"); break; }
                            String to = p2[0];
                            String text = p2[1];
                            if (!clients.containsKey(to)) { out.println("ERR user not online"); break; }
                            if (text.length() > MAX_TEXT) { out.println("ERR message too long"); break; }
                            sendDM(username, to, text);
                            out.println("OK dm sent to " + to);
                            break;
                        }

                        case "USERS": {
                            if (!ensureLogin()) break;
                            String list = String.join(",", clients.keySet());
                            out.println("USERS " + list);
                            break;
                        }

                        case "ATTACH": {
                            if (!ensureLogin()) break;
                            if (parts.length < 3) {
                                out.println("ERR usage: ATTACH (#general|<user>) <filename> <size>");
                                break;
                            }
                            String target = parts[1];
                            String[] more = parts[2].split("\\s+");
                            if (more.length < 2) {
                                out.println("ERR ATTACH missing filename/size");
                                break;
                            }
                            String filename = more[0];
                            long size;
                            try { size = Long.parseLong(more[1]); }
                            catch (Exception e) { out.println("ERR size must be number"); break; }

                            uploadFile = filename;
                            uploadSize = size;
                            uploadChunks.clear();

                            if ("#general".equals(target)) {
                                uploadIsGeneral = true;
                                uploadPeer = null;
                            } else {
                                if (!clients.containsKey(target)) { out.println("ERR user not online"); break; }
                                uploadIsGeneral = false;
                                uploadPeer = target;
                            }
                            uploading = true;
                            out.println("OK attach begin; send DATA <base64> then ATTACH_END");
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
        System.out.println("[SERVER] users.db: " + new File("users.db").getAbsolutePath());
        s.start();
    }
}
