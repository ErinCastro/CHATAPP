package clientsw;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.Socket;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

public class ChatSwingApp extends JFrame {
    // ---------- connection / state ----------
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private String username = "";
    private volatile boolean serverGreeted = false; // set true when we read the first line

    // ---------- status ----------
    private final JLabel status = new JLabel("Disconnected");

    // ---------- Users sidebar ----------
    private final DefaultListModel<String> usersModel = new DefaultListModel<>();
    private final JList<String> usersList = new JList<>(usersModel);

    // ---------- Tabs ----------
    private final JTabbedPane tabs = new JTabbedPane();

    // General tab
    private final JTextArea generalArea = new JTextArea();

    // DM tab (list of conversations + per-user thread)
    private final DefaultListModel<String> dmPeersModel = new DefaultListModel<>();
    private final JList<String> dmPeersList = new JList<>(dmPeersModel);
    private final CardLayout dmCards = new CardLayout();
    private final JPanel dmThreadsPanel = new JPanel(dmCards);
    private final Map<String, JTextArea> dmThreads = new HashMap<>();

    // Input row (shared)
    private final JTextField input = new JTextField();

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm");

    public ChatSwingApp() {
        super("BuzzChat");
        installSystemLookAndFeel();

        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(1000, 650);
        setLocationRelativeTo(null);

        // ----- LEFT: Online users -----
        usersList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        usersList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    String sel = usersList.getSelectedValue();
                    if (sel != null && !sel.equals(username)) {
                        ensureDmThread(sel);
                        tabs.setSelectedIndex(1); // DMs tab
                        dmPeersList.setSelectedValue(sel, true);
                        dmCards.show(dmThreadsPanel, sel);
                        input.requestFocus();
                    }
                }
            }
        });
        JScrollPane usersPane = new JScrollPane(usersList);
        usersPane.setPreferredSize(new Dimension(200, 200));
        JPanel left = new JPanel(new BorderLayout(6,6));
        left.setBorder(new EmptyBorder(8,8,8,8));
        left.add(new JLabel("Online Users"), BorderLayout.NORTH);
        left.add(usersPane, BorderLayout.CENTER);
        JLabel tip = new JLabel("Double-click a user to open DM");
        tip.setFont(tip.getFont().deriveFont(11f));
        left.add(tip, BorderLayout.SOUTH);

        // ----- CENTER: Tabs -----
        generalArea.setEditable(false);
        generalArea.setLineWrap(true);
        generalArea.setWrapStyleWord(true);
        JScrollPane generalScroll = new JScrollPane(generalArea);

        dmPeersList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        dmPeersList.addListSelectionListener(e -> {
            String sel = dmPeersList.getSelectedValue();
            if (sel != null) dmCards.show(dmThreadsPanel, sel);
            int dmIndexStar = tabs.indexOfTab("DMs *");
            if (dmIndexStar >= 0) tabs.setTitleAt(dmIndexStar, "DMs");
        });

        JSplitPane dmSplit = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(dmPeersList),
                dmThreadsPanel
        );
        dmSplit.setResizeWeight(0.25);

        tabs.addTab("General", generalScroll);
        tabs.addTab("DMs", dmSplit);

        // ----- TOP: clean header + real buttons row + status -----
        JPanel top = new JPanel(new BorderLayout(12,8));
        top.setBorder(new EmptyBorder(8,8,0,8));

        JLabel title = new JLabel("BuzzChat");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        top.add(title, BorderLayout.WEST);

        // Real raised buttons row
        JPanel buttonsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        JButton btnConnect  = button("Connect / Login");
        JButton btnRegister = button("Register");
        JButton btnUsers    = button("Users");
        JButton btnClear    = button("Clear");
        JButton btnQuit     = button("Quit");
        buttonsRow.add(btnConnect);
        buttonsRow.add(btnRegister);
        buttonsRow.add(btnUsers);
        buttonsRow.add(btnClear);
        buttonsRow.add(btnQuit);
        top.add(buttonsRow, BorderLayout.CENTER);

        status.setHorizontalAlignment(SwingConstants.RIGHT);
        top.add(status, BorderLayout.EAST);

        // Actions
        btnConnect.addActionListener(e -> connectDialog(false));
        btnRegister.addActionListener(e -> connectDialog(true));
        btnUsers.addActionListener(e -> send("USERS"));
        btnClear.addActionListener(e -> clearCurrentTab());
        btnQuit.addActionListener(e -> { send("QUIT"); close(); System.exit(0); });

        // Shortcuts
        bindShortcut(btnUsers, KeyStroke.getKeyStroke(KeyEvent.VK_U, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        bindShortcut(btnConnect, KeyStroke.getKeyStroke(KeyEvent.VK_L, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        bindShortcut(btnRegister, KeyStroke.getKeyStroke(KeyEvent.VK_R, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));

        // ----- BOTTOM: input row -----
        JPanel bottom = new JPanel(new BorderLayout(8,8));
        bottom.setBorder(new EmptyBorder(8,8,8,8));
        JButton sendBtn = button("Send");
        bottom.add(input, BorderLayout.CENTER);
        bottom.add(sendBtn, BorderLayout.EAST);
        input.addActionListener(e -> onSend());
        sendBtn.addActionListener(e -> onSend());

        // ----- LAYOUT: left + tabs + bottom -----
        JSplitPane centerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tabs, left);
        centerSplit.setResizeWeight(0.8);

        setJMenuBar(buildMenuBar()); // optional
        add(top, BorderLayout.NORTH);
        add(centerSplit, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
    }

    // Single dialog for host/port/user/pass and register toggle
    private void connectDialog(boolean precheckRegister) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(8,8,0,8));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4,4,4,4);
        c.anchor = GridBagConstraints.WEST;

        JTextField hostField = new JTextField("127.0.0.1", 16);
        JTextField portField = new JTextField("5050", 6);
        JTextField userField = new JTextField(username.isBlank() ? "alice" : username, 16);
        JPasswordField passField = new JPasswordField("", 16);
        JCheckBox chkRegister = new JCheckBox("Register new account");
        chkRegister.setSelected(precheckRegister);

        c.gridx=0; c.gridy=0; panel.add(new JLabel("Host:"), c);
        c.gridx=1; panel.add(hostField, c);
        c.gridx=0; c.gridy=1; panel.add(new JLabel("Port:"), c);
        c.gridx=1; panel.add(portField, c);
        c.gridx=0; c.gridy=2; panel.add(new JLabel("Username:"), c);
        c.gridx=1; panel.add(userField, c);
        c.gridx=0; c.gridy=3; panel.add(new JLabel("Password:"), c);
        c.gridx=1; panel.add(passField, c);
        c.gridx=1; c.gridy=4; panel.add(chkRegister, c);

        int ok = JOptionPane.showConfirmDialog(this, panel, "BuzzChat Connect",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;

        String host = hostField.getText().trim();
        String portStr = portField.getText().trim();
        String user = userField.getText().trim();
        String pass = new String(passField.getPassword());

        if (host.isBlank() || portStr.isBlank() || user.isBlank() || pass.isBlank()) {
            toast("All fields are required.");
            return;
        }
        if (!user.matches("[A-Za-z0-9_]{1,20}")) { toast("Invalid username format."); return; }

        int port;
        try { port = Integer.parseInt(portStr); }
        catch (Exception ex) { toast("Port must be a number."); return; }

        try {
            reconnect(host, port);

            // Wait for the server greeting so we don't race the welcome banner
            waitForServerGreeting(1500);

            if (chkRegister.isSelected()) {
                sendAndEcho("REGISTER " + user + " " + pass);
                sleep(200); // give server a moment to persist
            }

            sendAndEcho("LOGIN " + user + " " + pass);
            this.username = user;

            // refresh users after login
            sleep(120);
            sendAndEcho("USERS");
        } catch (Exception ex) {
            toast("Connect failed: " + ex.getMessage());
            close();
        }
    }

    // Make JButton look like a proper raised button everywhere
    private JButton button(String text) {
        JButton b = new JButton(text);
        b.setFocusPainted(false);
        b.setContentAreaFilled(true);
        b.setBorderPainted(true);
        b.setOpaque(true);
        b.setMargin(new Insets(6, 14, 6, 14));
        Dimension min = b.getMinimumSize();
        b.setMinimumSize(new Dimension(Math.max(90, min.width), Math.max(28, min.height)));
        return b;
    }

    private void installSystemLookAndFeel() {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}
    }

    private void bindShortcut(JButton btn, KeyStroke ks) {
        btn.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ks, btn.getText());
        btn.getActionMap().put(btn.getText(), new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                for (ActionListener al: btn.getActionListeners()) al.actionPerformed(e);
            }
        });
        btn.setToolTipText(btn.getText() + "  (" + keyStrokeText(ks) + ")");
    }

    private String keyStrokeText(KeyStroke ks) {
        String mod = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() == InputEvent.META_DOWN_MASK ? "⌘" : "Ctrl";
        return mod + "+" + KeyEvent.getKeyText(ks.getKeyCode());
    }

    private JMenuBar buildMenuBar() {
        JMenuBar mb = new JMenuBar();
        JMenu m = new JMenu("Connection");
        JMenuItem connect = new JMenuItem("Connect / Login");
        JMenuItem register = new JMenuItem("Register");
        JMenuItem usersCmd = new JMenuItem("Refresh Users");
        JMenuItem quit = new JMenuItem("Quit");
        connect.addActionListener(e -> connectDialog(false));
        register.addActionListener(e -> connectDialog(true));
        usersCmd.addActionListener(e -> send("USERS"));
        quit.addActionListener(e -> { send("QUIT"); close(); System.exit(0); });
        m.add(connect); m.add(register); m.add(usersCmd); m.addSeparator(); m.add(quit);
        mb.add(m);
        return mb;
    }

    private void reconnect(String host, int port) throws IOException {
        close();
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
        status.setText("Connected to " + host + ":" + port);
        serverGreeted = false;
        Thread t = new Thread(this::readerLoop, "server-reader");
        t.setDaemon(true);
        t.start();
    }

    private void readerLoop() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                final String L = line;
                SwingUtilities.invokeLater(() -> handleLine(L));
                serverGreeted = true; // first line sets this true
            }
        } catch (IOException e) {
            SwingUtilities.invokeLater(() -> toast("Disconnected: " + e.getMessage()));
        } finally {
            close();
        }
    }

    private void waitForServerGreeting(long maxMs) {
        long start = System.currentTimeMillis();
        while (!serverGreeted && System.currentTimeMillis() - start < maxMs) {
            sleep(20);
        }
    }

    // ---------- Incoming line handling ----------
    private void handleLine(String L) {
        if (L.startsWith("MSG ")) {
            // MSG <from> #general <text...>
            appendGeneral(L.substring(4));
        } else if (L.startsWith("DM ")) {
            // DM <from> <text...>
            int sp = L.indexOf(' ');
            String rest = (sp >= 0 ? L.substring(sp + 1) : "");
            int sp2 = rest.indexOf(' ');
            if (sp2 > 0) {
                String from = rest.substring(0, sp2);
                String body = rest.substring(sp2 + 1);
                appendDM(from, body);
            } else {
                appendDM("?", rest);
            }
        } else if (L.startsWith("USERS ")) {
            String csv = L.substring(6).trim();
            updateUsers(csv);
        } else if (L.startsWith("OK ")) {
            appendGeneral("[INFO] " + L.substring(3));
        } else if (L.startsWith("ERR ")) {
            appendGeneral("[ERR] " + L.substring(4));
        } else {
            appendGeneral("[RAW] " + L);
        }
    }

    private void updateUsers(String csv) {
        List<String> names = csv.isEmpty()
                ? List.of()
                : Arrays.asList(csv.split(","));
        names.sort(String.CASE_INSENSITIVE_ORDER);
        usersModel.clear();
        for (String s : names) usersModel.addElement(s);
        status.setText("Online: " + usersModel.size());
    }

    // ---------- Sending ----------
    private void onSend() {
        String text = input.getText().trim();
        if (text.isEmpty()) return;

        int tab = tabs.getSelectedIndex();
        if (tab == 0) {
            // General tab
            if (isRawProtocol(text)) {
                sendAndEcho(text);
            } else if (text.startsWith("/dm ")) {
                String rest = text.substring(4).trim();
                int sp = rest.indexOf(' ');
                if (sp <= 0) toast("Usage: /dm <user> <text>");
                else sendAndEcho("DM " + rest.substring(0, sp) + " " + rest.substring(sp + 1));
            } else {
                sendAndEcho("MSG #general " + text);
            }
        } else {
            // DM tab
            String peer = dmPeersList.getSelectedValue();
            if (peer == null) {
                toast("Select a DM conversation on the left.");
            } else if (isRawProtocol(text)) {
                sendAndEcho(text);
            } else {
                // optimistic echo so sender also sees their message immediately
                ensureDmThread(peer);
                JTextArea area = dmThreads.get(peer);
                area.append(tsNow() + "  You → " + peer + ": " + text + "\n");
                area.setCaretPosition(area.getDocument().getLength());

                sendAndEcho("DM " + peer + " " + text);
            }
        }
        input.setText("");
    }

    private boolean isRawProtocol(String text) {
        return text.startsWith("LOGIN ") || text.startsWith("REGISTER ")
                || text.equals("USERS") || text.equals("QUIT")
                || text.startsWith("DM ") || text.startsWith("MSG ");
    }

    private void send(String line) {
        if (out == null) { toast("Not connected. Click 'Connect / Login' first."); return; }
        out.println(line);
    }

    private void sendAndEcho(String line) {
        appendGeneral("[SEND] " + line);
        send(line);
    }

    // ---------- General / DM append helpers ----------
    private void appendGeneral(String s) {
        generalArea.append(tsNow() + "  " + s + "\n");
        generalArea.setCaretPosition(generalArea.getDocument().getLength());
    }

    private void appendDM(String from, String body) {
        String peer = from.equals(username) ? extractPeerFromEcho(body) : from;
        ensureDmThread(peer);
        JTextArea area = dmThreads.get(peer);

        if (from.equals(username)) {
            String clean = body.replaceFirst("\\[to\\s+" + Pattern.quote(peer) + "\\]\\s*", "");
            area.append(tsNow() + "  You → " + peer + ": " + clean + "\n");
        } else {
            area.append(tsNow() + "  " + from + ": " + body + "\n");
        }
        area.setCaretPosition(area.getDocument().getLength());

        int dmIndex = tabs.indexOfTab("DMs");
        if (dmIndex >= 0 && tabs.getSelectedIndex() != dmIndex) {
            tabs.setTitleAt(dmIndex, "DMs *");
        }
    }

    private String extractPeerFromEcho(String body) {
        int a = body.indexOf("[to ");
        int b = body.indexOf(']', a + 4);
        if (a >= 0 && b > a) return body.substring(a + 4, b).trim();
        return "?";
    }

    private void ensureDmThread(String peer) {
        if (!dmThreads.containsKey(peer)) {
            JTextArea area = new JTextArea();
            area.setEditable(false);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            JScrollPane sc = new JScrollPane(area);
            dmThreads.put(peer, area);
            dmThreadsPanel.add(sc, peer);
            if (!contains(dmPeersModel, peer)) dmPeersModel.addElement(peer);
        }
        int dmIndexStar = tabs.indexOfTab("DMs *");
        if (dmIndexStar >= 0 && tabs.getSelectedIndex() == dmIndexStar) {
            tabs.setTitleAt(dmIndexStar, "DMs");
        }
    }

    private static boolean contains(DefaultListModel<String> model, String s) {
        for (int i = 0; i < model.size(); i++) if (Objects.equals(model.get(i), s)) return true;
        return false;
    }

    // ---------- UI utilities ----------
    private void toast(String s) {
        appendGeneral(s);
        status.setText(s);
    }

    private String prompt(String msg, String def) {
        String val = (String) JOptionPane.showInputDialog(
                this, msg, "BuzzChat", JOptionPane.PLAIN_MESSAGE, null, null, def);
        return val == null ? null : val.trim();
    }

    private boolean confirm(String msg) {
        int opt = JOptionPane.showConfirmDialog(this, msg, "BuzzChat",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        return opt == JOptionPane.YES_OPTION;
    }

    private void clearCurrentTab() {
        int idx = tabs.getSelectedIndex();
        if (idx == 0) {
            generalArea.setText("");
        } else {
            String peer = dmPeersList.getSelectedValue();
            if (peer != null) {
                JTextArea area = dmThreads.get(peer);
                if (area != null) area.setText("");
            }
        }
    }

    private String tsNow() { return LocalTime.now().format(TS); }

    private void close() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        socket = null; in = null; out = null;
        status.setText("Disconnected");
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ChatSwingApp().setVisible(true));
    }
}
