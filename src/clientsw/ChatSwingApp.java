package clientsw;

import java.awt.*;
import java.awt.TrayIcon.MessageType;
import java.awt.event.*;
import java.io.*;
import java.net.Socket;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;
import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;

public class ChatSwingApp extends JFrame {
    // ---------- connection / state ----------
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private String username = "";
    private volatile boolean serverGreeted = false;

    // ---------- status ----------
    private final JLabel status = new JLabel("Disconnected");

    // ---------- Users sidebar ----------
    private final DefaultListModel<String> usersModel = new DefaultListModel<>();
    private final JList<String> usersList = new JList<>(usersModel);

    // ---------- Tabs ----------
    private final JTabbedPane tabs = new JTabbedPane();

    // General tab (bubble panel)
    private final BubbleScroll generalScroll;
    private final BubblePanel generalPanel;

    // DM tab: list of conversations + per-user thread (bubble panels)
    private final DefaultListModel<String> dmPeersModel = new DefaultListModel<>();
    private final JList<String> dmPeersList = new JList<>(dmPeersModel);
    private final CardLayout dmCards = new CardLayout();
    private final JPanel dmThreadsPanel = new JPanel(dmCards);
    private final Map<String, BubblePanel> dmThreads = new HashMap<>();
    
    // Unread message tracking
    private final Map<String, Integer> unreadCounts = new HashMap<>();
    private int totalUnreadDMs = 0;

    // Input row (shared)
    private final JTextField input = new JTextField();

    // Typing indicator helpers
    private javax.swing.Timer typingStopper;
    private volatile boolean typingSentStart = false;

    // Attachments (incoming): id -> (buffer, suggested name)
    private final Map<Long, ByteArrayOutputStream> incomingFiles = new HashMap<>();
    private final Map<Long, String> incomingFilenames = new HashMap<>();

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm");

    // ---- Dark theme palette ----
    private static final Color BG = new Color(0x121212);
    private static final Color SURFACE = new Color(0x1E1E1E);
    private static final Color SURFACE2 = new Color(0x242424);
    private static final Color ON_BG = new Color(0xE6E6E6);
    private static final Color ON_MUTE = new Color(0xA8A8A8);
    private static final Color PRIMARY = new Color(0x42A5F5);
    private static final Color PRIMARY_D = new Color(0x1E88E5);
    private static final Color ME_BUBBLE = new Color(0x2E7D32); // green-ish for self
    private static final Color OTHER_BUB = new Color(0x333333);
    private static final Color BORDER = new Color(0x2A2A2A);

    public ChatSwingApp() {
        super("BuzzChat");
        installDarkLookAndFeel();

        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(1100, 700);
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG);

        // ----- LEFT: Online users -----
        usersList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        usersList.setCellRenderer(new UserCell());
        usersList.setBackground(SURFACE);
        usersList.setForeground(ON_BG);
        usersList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    String sel = usersList.getSelectedValue();
                    if (sel != null && !sel.equals(username)) {
                        ensureDmThread(sel);
                        tabs.setSelectedIndex(1); // DMs tab
                        dmPeersList.setSelectedValue(sel, true);
                        dmCards.show(dmThreadsPanel, sel);
                        input.requestFocusInWindow();
                    }
                }
            }
        });
        JScrollPane usersPane = wrap(usersList);
        usersPane.setPreferredSize(new Dimension(230, 200));

        JPanel left = panel(new BorderLayout(6, 6));
        JLabel usersLbl = label("Online Users", true);
        left.add(usersLbl, BorderLayout.NORTH);
        left.add(usersPane, BorderLayout.CENTER);
        JLabel tip = small("Double-click a user to open DM");
        left.add(tip, BorderLayout.SOUTH);

        // ----- CENTER: Tabs (bubble chat panels) -----
        generalPanel = new BubblePanel();
        generalScroll = new BubbleScroll(generalPanel);

        dmPeersList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        dmPeersList.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        dmPeersList.setBackground(SURFACE);
        dmPeersList.setForeground(ON_BG);
        PeerCell peerCellRenderer = new PeerCell();
        peerCellRenderer.setParent(this);
        dmPeersList.setCellRenderer(peerCellRenderer);
        dmPeersList.addListSelectionListener(e -> {
            String sel = dmPeersList.getSelectedValue();
            if (sel != null) {
                dmCards.show(dmThreadsPanel, sel);
                // Mark messages as read when viewing a conversation
                clearUnreadForPeer(sel);
            }
        });

        JScrollPane peerScroll = wrap(dmPeersList);
        dmThreadsPanel.setLayout(dmCards);
        dmThreadsPanel.setBackground(SURFACE);

        JSplitPane dmSplit = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                peerScroll,
                wrap(dmThreadsPanel)
        );
        dmSplit.setResizeWeight(0.28);
        paintSplit(dmSplit);

        tabs.setBackground(SURFACE);
        tabs.setForeground(ON_BG);
        tabs.addTab("General", generalScroll);
        tabs.addTab("DMs", dmSplit);
        
        // Listen for tab changes to clear unread when DM tab is selected
        tabs.addChangeListener(e -> {
            if (tabs.getSelectedIndex() == 1) {
                String sel = dmPeersList.getSelectedValue();
                if (sel != null) {
                    clearUnreadForPeer(sel);
                }
            }
        });

        // ----- TOP: title + buttons + status -----
        JPanel top = panel(new BorderLayout(12, 8));
        JLabel title = label("BuzzChat", true);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        top.add(title, BorderLayout.WEST);

        JPanel buttonsRow = flowLeft(10);
        JButton btnConnect = button("Connect / Login");
        JButton btnRegister = button("Register");
        JButton btnUsers = button("Users");
        JButton btnClear = button("Clear");
        JButton btnQuit = dangerButton("Quit");
        buttonsRow.add(btnConnect);
        buttonsRow.add(btnRegister);
        buttonsRow.add(btnUsers);
        buttonsRow.add(btnClear);
        buttonsRow.add(btnQuit);
        top.add(buttonsRow, BorderLayout.CENTER);

        status.setHorizontalAlignment(SwingConstants.RIGHT);
        status.setForeground(ON_MUTE);
        top.add(status, BorderLayout.EAST);

        btnConnect.addActionListener(e -> connectDialog(false));
        btnRegister.addActionListener(e -> connectDialog(true));
        btnUsers.addActionListener(e -> send("USERS"));
        btnClear.addActionListener(e -> clearCurrentTab());
        btnQuit.addActionListener(e -> {
            send("QUIT");
            close();
            System.exit(0);
        });

        bindShortcut(btnUsers, KeyStroke.getKeyStroke(KeyEvent.VK_U, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        bindShortcut(btnConnect, KeyStroke.getKeyStroke(KeyEvent.VK_L, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        bindShortcut(btnRegister, KeyStroke.getKeyStroke(KeyEvent.VK_R, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));

        // ----- BOTTOM: input + attach + send -----
        JPanel bottom = panel(new BorderLayout(8, 8));
        JButton attachBtn = button("Attach…");
        JButton sendBtn = primaryButton("Send");
        styleField(input);
        bottom.add(attachBtn, BorderLayout.WEST);
        bottom.add(input, BorderLayout.CENTER);
        bottom.add(sendBtn, BorderLayout.EAST);
        input.addActionListener(e -> onSend());
        sendBtn.addActionListener(e -> onSend());
        attachBtn.addActionListener(e -> onAttach());

        // Typing indicator timers
        typingStopper = new javax.swing.Timer(1200, e -> sendTypingStop());
        typingStopper.setRepeats(false);
        input.addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                onTyping();
            }
        });

        // ----- LAYOUT: left + tabs + bottom -----
        JSplitPane centerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tabs, wrap(left));
        centerSplit.setResizeWeight(0.77);
        paintSplit(centerSplit);

        setJMenuBar(buildMenuBar());
        add(top, BorderLayout.NORTH);
        add(centerSplit, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
    }

    private void showNotification(String title, String message) {
        if (SystemTray.isSupported()) {
            SystemTray systemTray = SystemTray.getSystemTray();
            TrayIcon trayIcon;

            // Try to reuse existing icon if available, otherwise create a new one
            if (systemTray.getTrayIcons().length > 0) {
                trayIcon = systemTray.getTrayIcons()[0];
            } else {
                trayIcon = new TrayIcon(Toolkit.getDefaultToolkit().getImage("icon.png"));
                trayIcon.setImageAutoSize(true);
                try {
                    systemTray.add(trayIcon);
                } catch (AWTException e) {
                    System.err.println("Could not add TrayIcon: " + e.getMessage());
                    return;
                }
            }

            trayIcon.displayMessage(title, message, MessageType.INFO);
        } else {
            System.out.println("System Tray not supported");
        }
    }

    private void playNotificationSound() {
        try {
            AudioInputStream audioIn = AudioSystem.getAudioInputStream(getClass().getResource("notification.wav"));
            Clip clip = AudioSystem.getClip();
            clip.open(audioIn);
            clip.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ===== Dark-styled file chooser =====
    private JFileChooser makeDarkFileChooser(String title, String suggestedName) {
        // Make sure the chooser picks up dark colors
        UIManager.put("FileChooser.background", SURFACE);
        UIManager.put("FileChooser.foreground", ON_BG);
        UIManager.put("FileChooser.listViewBackground", SURFACE2);
        UIManager.put("FileChooser.listViewForeground", ON_BG);
        UIManager.put("FileChooser.disabledText", ON_MUTE);
        UIManager.put("FileChooser.accents", PRIMARY);
        UIManager.put("Panel.background", SURFACE);
        UIManager.put("Label.foreground", ON_BG);
        UIManager.put("TextField.background", SURFACE2);
        UIManager.put("TextField.foreground", ON_BG);
        UIManager.put("ComboBox.background", SURFACE2);
        UIManager.put("ComboBox.foreground", ON_BG);

        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(title);
        if (suggestedName != null && !suggestedName.isBlank()) {
            fc.setSelectedFile(new File(suggestedName));
        }
        SwingUtilities.updateComponentTreeUI(fc);
        return fc;
    }

    // ===== Connection dialog (with Register option) =====
    private void connectDialog(boolean precheckRegister) {
        JPanel panel = panel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;

        JTextField hostField = field("127.0.0.1", 16);
        JTextField portField = field("5050", 6);
        JTextField userField = field(username.isBlank() ? "alice" : username, 16);
        JPasswordField passField = passField("", 16);
        JCheckBox chkRegister = check("Register new account");
        chkRegister.setSelected(precheckRegister);

        c.gridx = 0;
        c.gridy = 0;
        panel.add(label("Host:"), c);
        c.gridx = 1;
        panel.add(hostField, c);
        c.gridx = 0;
        c.gridy = 1;
        panel.add(label("Port:"), c);
        c.gridx = 1;
        panel.add(portField, c);
        c.gridx = 0;
        c.gridy = 2;
        panel.add(label("Username:"), c);
        c.gridx = 1;
        panel.add(userField, c);
        c.gridx = 0;
        c.gridy = 3;
        panel.add(label("Password:"), c);
        c.gridx = 1;
        panel.add(passField, c);
        c.gridx = 1;
        c.gridy = 4;
        panel.add(chkRegister, c);

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
        if (!user.matches("[A-Za-z0-9_]{1,20}")) {
            toast("Invalid username format.");
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (Exception ex) {
            toast("Port must be a number.");
            return;
        }

        try {
            reconnect(host, port);
            waitForServerGreeting(1500);

            if (chkRegister.isSelected()) {
                sendAndEcho("REGISTER " + user + " " + pass);
                sleep(200);
            }

            sendAndEcho("LOGIN " + user + " " + pass);
            this.username = user;

            sleep(120);
            sendAndEcho("USERS");
        } catch (Exception ex) {
            toast("Connect failed: " + ex.getMessage());
            close();
        }
    }

    // ===== Buttons / UI helpers (dark) =====
    private void installDarkLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        UIManager.put("Panel.background", BG);
        UIManager.put("OptionPane.background", SURFACE);
        UIManager.put("OptionPane.messageForeground", ON_BG);
        UIManager.put("TextField.background", SURFACE2);
        UIManager.put("TextField.foreground", ON_BG);
        UIManager.put("TextField.caretForeground", ON_BG);
        UIManager.put("TextField.selectionBackground", PRIMARY_D);
        UIManager.put("Label.foreground", ON_BG);
        UIManager.put("ScrollPane.background", SURFACE);
        UIManager.put("TabbedPane.contentAreaColor", SURFACE);
        UIManager.put("TabbedPane.background", SURFACE);
        UIManager.put("TabbedPane.foreground", ON_BG);
        UIManager.put("List.background", SURFACE);
        UIManager.put("List.foreground", ON_BG);
    }

    private JPanel panel(LayoutManager lm) {
        JPanel p = new JPanel(lm);
        p.setBackground(SURFACE);
        p.setBorder(new EmptyBorder(8, 8, 8, 8));
        return p;
    }

    private JPanel panel() {
        JPanel p = new JPanel();
        p.setBackground(SURFACE);
        return p;
    }

    private JLabel label(String s) {
        JLabel l = new JLabel(s);
        l.setForeground(ON_BG);
        return l;
    }

    private JLabel label(String s, boolean bold) {
        JLabel l = label(s);
        if (bold) l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    private JLabel small(String s) {
        JLabel l = label(s);
        l.setForeground(ON_MUTE);
        l.setFont(l.getFont().deriveFont(11f));
        return l;
    }

    private JTextField field(String def, int cols) {
        JTextField f = new JTextField(def, cols);
        styleField(f);
        return f;
    }

    private JPasswordField passField(String def, int cols) {
        JPasswordField f = new JPasswordField(def, cols);
        styleField(f);
        return f;
    }

    private void styleField(JTextField f) {
        f.setBackground(SURFACE2);
        f.setForeground(ON_BG);
        f.setCaretColor(ON_BG);
        f.setBorder(new LineBorder(BORDER, 1, true));
        f.setFont(new Font("Segoe UI", Font.PLAIN, 14));
    }

    private JCheckBox check(String s) {
        JCheckBox c = new JCheckBox(s);
        c.setOpaque(false);
        c.setForeground(ON_BG);
        return c;
    }

    private JScrollPane wrap(Component comp) {
        JScrollPane sp = new JScrollPane(comp);
        sp.getViewport().setBackground(SURFACE);
        sp.setBorder(new LineBorder(BORDER));
        return sp;
    }

    private void paintSplit(JSplitPane sp) {
        sp.setBorder(new LineBorder(BORDER));
        sp.setBackground(SURFACE);
        sp.setDividerSize(6);
    }

    private JPanel flowLeft(int gap) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, gap, 0));
        p.setOpaque(false);
        return p;
    }

    private JButton baseButton(String text) {
        JButton b = new JButton(text);
        b.setFocusPainted(false);
        b.setContentAreaFilled(true);
        b.setBorderPainted(false);
        b.setOpaque(true);
        b.setMargin(new Insets(8, 16, 8, 16));
        b.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setBackground(SURFACE2);
        b.setForeground(ON_BG);
        b.setBorder(new LineBorder(BORDER, 1, true));
        return b;
    }

    private JButton button(String text) {
        return baseButton(text);
    }

    private JButton primaryButton(String text) {
        JButton b = baseButton(text);
        b.setBackground(PRIMARY);
        b.setForeground(Color.BLACK);
        b.setBorder(new LineBorder(PRIMARY_D, 1, true));
        return b;
    }

    private JButton dangerButton(String text) {
        JButton b = baseButton(text);
        b.setBackground(new Color(0xEF5350));
        b.setBorder(new LineBorder(new Color(0xE53935), 1, true));
        return b;
    }

    private void bindShortcut(JButton btn, KeyStroke ks) {
        btn.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ks, btn.getText());
        btn.getActionMap().put(btn.getText(), new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                for (ActionListener al : btn.getActionListeners()) al.actionPerformed(e);
            }
        });
        btn.setToolTipText(btn.getText() + "  (" + keyStrokeText(ks) + ")");
    }

    private String keyStrokeText(KeyStroke ks) {
        boolean isMac = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");
        String mod = isMac ? "⌘" : "Ctrl";
        return mod + "+" + KeyEvent.getKeyText(ks.getKeyCode());
    }

    private JMenuBar buildMenuBar() {
        JMenuBar mb = new JMenuBar();
        mb.setBackground(SURFACE);
        mb.setBorder(new LineBorder(BORDER));
        JMenu m = new JMenu("Connection");
        m.setForeground(ON_BG);
        JMenuItem connect = new JMenuItem("Connect / Login");
        JMenuItem register = new JMenuItem("Register");
        JMenuItem usersCmd = new JMenuItem("Refresh Users");
        JMenuItem quit = new JMenuItem("Quit");
        connect.addActionListener(e -> connectDialog(false));
        register.addActionListener(e -> connectDialog(true));
        usersCmd.addActionListener(e -> send("USERS"));
        quit.addActionListener(e -> {
            send("QUIT");
            close();
            System.exit(0);
        });
        m.add(connect);
        m.add(register);
        m.add(usersCmd);
        m.addSeparator();
        m.add(quit);
        mb.add(m);
        return mb;
    }

    // ===== Connect / reader loop =====
    private void reconnect(String host, int port) throws IOException {
    close();
    
    // Clear all DM threads to prevent duplicates on reconnection
    dmThreads.clear();
    dmThreadsPanel.removeAll();
    dmPeersModel.clear();
    unreadCounts.clear();
    totalUnreadDMs = 0;
    updateDMTabBadge();
    
    socket = new Socket(host, port);
    socket.setTcpNoDelay(true);
    in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
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
                serverGreeted = true;
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

    // ===== Incoming line handling =====
    private void handleLine(String L) {
       if (L.startsWith("MSG ")) {
    // MSG <id> <from> #general <text...> <timestamp>
    int i1 = L.indexOf(' ');
    String rest = (i1 >= 0 ? L.substring(i1 + 1) : "");
    int i2 = rest.indexOf(' ');
    int i3 = rest.indexOf(' ', i2 + 1);
    int i4 = rest.indexOf(' ', i3 + 1);
    int i5 = rest.indexOf(' ', i4 + 1); // Find timestamp position

    if (i1 >= 0 && i2 > 0 && i3 > 0 && i4 > 0 && i5 > 0) {
        String from = rest.substring(i2 + 1, i3);
        String text = rest.substring(i4 + 1, i5);  // Extract message text
        String timestamp = rest.substring(i5 + 1);  // Extract timestamp
        appendGeneralBubble(from, text, from.equals(username), timestamp);  // Display the message with the timestamp
    } else {
        appendGeneralBubble("?", L.substring(4), false, "Invalid timestamp");
    }
}
else if (L.startsWith("DM ")) {
            // DM <id> <from> <text...>
            String[] p = L.split("\\s+", 4);
            if (p.length >= 4) {
                long id = parseLongSafe(p[1]);
                String from = p[2];
                String body = p[3];

    boolean selfSent = from.equals(username);

    // Get the timestamp
    String timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));

    // Pass the timestamp to the appendDMBubble method
    appendDMBubble(from, body, selfSent, timestamp);

    // Check current view state
    boolean dmTabVisible = (tabs.getSelectedIndex() == 1);
    String selPeer = dmPeersList.getSelectedValue();
    boolean viewingThisPeer = (selPeer != null && selPeer.equals(from));
    boolean windowActive = this.isActive(); // frame focus

    if (!selfSent) {
        // Always increment unread count and show notification
        incrementUnreadForPeer(from);
        playNotificationSound();

        // Show notification with unread badge
        int unreadCount = unreadCounts.getOrDefault(from, 0);
        String notifTitle = "New DM from " + from;
        if (unreadCount > 1) {
            notifTitle += " (" + unreadCount + " unread)";
        }
        showNotification(notifTitle, body);

        // Only send READ if actively viewing this conversation
        if (dmTabVisible && viewingThisPeer && windowActive) {
            send("READ " + id);
        }
    }
}
 else {
                appendDMBubble("?", L.substring(3), false, tsNow());
            }
        } else if (L.startsWith("USERS ")) {
            String csv = L.substring(6).trim();
            updateUsers(csv);
        } else if (L.startsWith("OK ")) {
            appendGeneralSystem("INFO", L.substring(3));
        } else if (L.startsWith("ERR ")) {
            appendGeneralSystem("ERR", L.substring(4));
        } else if (L.startsWith("READ ")) {
            // READ <id> <by>
            String[] p = L.split("\\s+");
            if (p.length >= 3) {
                String by = p[2];
                String peer = by; // reader is the peer
                ensureDmThread(peer);
                BubblePanel area = dmThreads.get(peer);
                area.addSystem("(read by " + by + ")");
                area.scrollToBottom();
            }
        } else if (L.startsWith("TYPING ")) {
            // TYPING <from> #general START|STOP  OR  TYPING <from> <to> START|STOP
            String[] p = L.split("\\s+");
            if (p.length >= 4) {
                String from = p[1];
                String tgt = p[2];
                String state = p[3];
                showTypingBanner(from, tgt, state);
            }
        } else if (L.startsWith("FILE ")) {
            // General: FILE <id> <from> #general <filename> <size>
            // Echo:    FILE <id> <from> [to <peer>] <filename> <size>
            // DM in:   FILE <id> <from> <filename> <size>
            String[] p = L.split("\\s+");
            if (p.length >= 6) {
                long id = parseLongSafe(p[1]);
                String from = p[2];

                if ("#general".equals(p[3])) {
                    String filename = p[4];
                    String sizeStr = p[5];
                    incomingFiles.put(id, new ByteArrayOutputStream());
                    incomingFilenames.put(id, filename);
                    appendGeneralSystem("FILE", from + " shared: " + filename + " (" + sizeStr + " bytes)");
                } else if ("[to".equals(p[3])) {
                    // echo to sender
                    String peer = p[4].substring(0, p[4].length() - 1);
                    String filename = p[5];
                    String sizeStr = p[6];
                    incomingFiles.put(id, new ByteArrayOutputStream());
                    incomingFilenames.put(id, filename);
                    ensureDmThread(peer);
                    dmThreads.get(peer).addSystem("You sent file: " + filename + " (" + sizeStr + " bytes)");
                } else {
                    // DM incoming
                    String filename = p[3];
                    String sizeStr = p[4];
                    incomingFiles.put(id, new ByteArrayOutputStream());
                    incomingFilenames.put(id, filename);
                    ensureDmThread(from);
                    dmThreads.get(from).addSystem(from + " sent file: " + filename + " (" + sizeStr + " bytes)");
                }
            }
        } else if (L.startsWith("FILE_DATA ")) {
            // FILE_DATA <id> <base64>
            String[] p = L.split("\\s+", 3);
            if (p.length >= 3) {
                long id = parseLongSafe(p[1]);
                ByteArrayOutputStream baos = incomingFiles.get(id);
                if (baos != null) {
                    byte[] chunk = Base64.getDecoder().decode(p[2]);
                    try {
                        baos.write(chunk);
                    } catch (IOException ignored) {
                    }
                }
            }
        } else if (L.startsWith("FILE_END ")) {
            // FILE_END <id> -> ask to save
            String[] p = L.split("\\s+");
            if (p.length >= 2) {
                long id = parseLongSafe(p[1]);
                ByteArrayOutputStream baos = incomingFiles.remove(id);
                String fname = incomingFilenames.remove(id);
                if (baos != null) {
                    JFileChooser fc = makeDarkFileChooser("Save received file", fname);
                    if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                        try (FileOutputStream fos = new FileOutputStream(fc.getSelectedFile())) {
                            baos.writeTo(fos);
                            toast("Saved file: " + fc.getSelectedFile().getAbsolutePath());
                        } catch (IOException ex) {
                            toast("Failed to save file: " + ex.getMessage());
                        }
                    }
                }
            }
        } else {
            appendGeneralSystem("RAW", L);
        }
    }

    private void updateUsers(String csv) {
        List<String> names = (csv == null || csv.isBlank())
                ? new ArrayList<>()
                : new ArrayList<>(Arrays.asList(csv.split(",")));
        names.replaceAll(String::trim);
        names.removeIf(String::isBlank);
        names.sort(String.CASE_INSENSITIVE_ORDER);

        usersModel.clear();
        for (String s : names) {
            usersModel.addElement(s);
        }
        status.setText("Online: " + usersModel.getSize());
    }

    private long parseLongSafe(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return -1;
        }
    }

    private void showTypingBanner(String from, String tgt, String state) {
        if ("#general".equals(tgt)) {
            generalPanel.addSystem((state.equals("START") ? "✎ " : "✓ ") + from +
                    (state.equals("START") ? " is typing..." : " stopped typing."));
            generalPanel.scrollToBottom();
        } else {
            ensureDmThread(from);
            BubblePanel area = dmThreads.get(from);
            area.addSystem((state.equals("START") ? "✎ " : "✓ ") + from +
                    (state.equals("START") ? " is typing..." : " stopped typing."));
            area.scrollToBottom();
        }
    }

    // ===== Send path / input handling =====
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
            else {
                // REMOVE THESE LINES - they cause duplication:
                // String peer = rest.substring(0, sp);
                // String dmText = rest.substring(sp + 1);
                // String timestamp = tsNow();
                // ensureDmThread(peer);
                // appendDMBubble(username, "[to " + peer + "] " + dmText, true, timestamp);
                
                // KEEP ONLY THIS:
                sendAndEcho("DM " + rest.substring(0, sp) + " " + rest.substring(sp + 1));
            }
        } else {
            // General chat message - ALSO REMOVE local echo here
            String timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
            // REMOVE: appendGeneralBubble(username, text, true, timestamp);
            // KEEP ONLY:
            sendAndEcho("MSG #general " + text + " " + timestamp);
        }
    } else {
        // DM tab
        String peer = dmPeersList.getSelectedValue();
        if (peer == null) {
            toast("Select a DM conversation on the left.");
        } else if (isRawProtocol(text)) {
            sendAndEcho(text);
        } else {
            // Send the DM with timestamp - REMOVE local echo
            String timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
            // REMOVE: appendDMBubble(username, "[to " + peer + "] " + text, true, timestamp);
            // KEEP ONLY:
            sendAndEcho("DM " + peer + " " + text + " " + timestamp);
        }
    }
    input.setText("");

    if (typingSentStart) {
        sendTypingStop();
        typingStopper.stop();
    }
}


    private boolean isRawProtocol(String text) {
        return text.startsWith("LOGIN ") || text.startsWith("REGISTER ")
                || text.equals("USERS") || text.equals("QUIT")
                || text.startsWith("DM ") || text.startsWith("MSG ")
                || text.startsWith("HISTORY ") || text.startsWith("TYPING ")
                || text.startsWith("READ ") || text.startsWith("ATTACH ")
                || text.startsWith("DATA ") || text.equals("ATTACH_END");
    }

    private void onTyping() {
        int tab = tabs.getSelectedIndex();
        String cmd;
        if (tab == 0) {
            cmd = "TYPING #general START";
        } else {
            String peer = dmPeersList.getSelectedValue();
            if (peer == null) return;
            cmd = "TYPING " + peer + " START";
        }
        if (!typingSentStart) {
            send(cmd);
            typingSentStart = true;
        }
        typingStopper.restart();
    }

    private void sendTypingStop() {
        int tab = tabs.getSelectedIndex();
        if (tab == 0) send("TYPING #general STOP");
        else {
            String peer = dmPeersList.getSelectedValue();
            if (peer != null) send("TYPING " + peer + " STOP");
        }
        typingSentStart = false;
    }

    private void onAttach() {
        int tab = tabs.getSelectedIndex();
        boolean toGeneral = (tab == 0);
        String peer = null;
        if (!toGeneral) {
            peer = dmPeersList.getSelectedValue();
            if (peer == null) {
                toast("Select a DM conversation on the left.");
                return;
            }
        }

        JFileChooser fc = makeDarkFileChooser("Choose a file to send", null);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        long size = f.length();

        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = fis.readAllBytes();
            String filename = f.getName();
            if (toGeneral) send("ATTACH #general " + filename + " " + size);
            else send("ATTACH " + peer + " " + filename + " " + size);

            // chunk base64 (~3KB per line)
            int off = 0, chunk = 3072;
            while (off < buf.length) {
                int n = Math.min(chunk, buf.length - off);
                String b64 = Base64.getEncoder().encodeToString(Arrays.copyOfRange(buf, off, off + n));
                send("DATA " + b64);
                off += n;
            }
            send("ATTACH_END");
        } catch (IOException ex) {
            toast("Attach failed: " + ex.getMessage());
        }
    }

    private void send(String line) {
        if (out == null) {
            toast("Not connected. Click 'Connect / Login' first.");
            return;
        }
        out.println(line);
    }

    private void sendAndEcho(String line) {
        appendGeneralSystem("SEND", line);
        send(line);
    }

    // ===== Append helpers (bubbles) =====
        private void appendGeneralBubble(String from, String text, boolean self, String timestamp) {
            String meta = from + "  •  " + timestamp + "  #general"; // Use timestamp here
            generalPanel.addBubble(from, meta, text, self, timestamp); // Pass timestamp to addBubble
            generalPanel.scrollToBottom();
        }


    private void appendGeneralSystem(String tag, String text) {
        generalPanel.addSystem("[" + tag + "] " + text);
        generalPanel.scrollToBottom();
    }

       private void appendDMBubble(String from, String body, boolean self, String timestamp) {
            String meta;
            String clean = body;
            
            // Determine the peer for this conversation
            String peerName;  // Changed from 'peer' to 'peerName'
            if (self) {
                peerName = extractPeerFromEcho(body); // If self-sent, peer is the recipient
                // Remove the protocol echo marker from the displayed text
                clean = body.replaceFirst("\\[to\\s+" + Pattern.quote(peerName) + "\\]\\s*", "");
                meta = "You → " + peerName + "  •  " + timestamp;
            } else {
                peerName = from; // If incoming, peer is the sender
                meta = from + "  •  " + timestamp;
            }

            ensureDmThread(peerName); // Ensure the thread exists before using it
            dmThreads.get(peerName).addBubble(from, meta, clean, self, timestamp); // Pass timestamp
            dmThreads.get(peerName).scrollToBottom();
        }


    private String extractPeerFromEcho(String body) {
        int a = body.indexOf("[to ");
        int b = body.indexOf(']', a + 4);
        if (a >= 0 && b > a) return body.substring(a + 4, b).trim();
        return "?";
    }

   private void ensureDmThread(String peer) {
    if (!dmThreads.containsKey(peer)) {
        BubblePanel area = new BubblePanel();
        JScrollPane sc = new BubbleScroll(area);
        dmThreads.put(peer, area);
        dmThreadsPanel.add(sc, peer);
        if (!contains(dmPeersModel, peer)) {
            dmPeersModel.addElement(peer);
        }
        // Initialize unread count for new peer
        if (!unreadCounts.containsKey(peer)) {
            unreadCounts.put(peer, 0);
        }
    }
}
    
    private void incrementUnreadForPeer(String peer) {
    int current = unreadCounts.getOrDefault(peer, 0);
    unreadCounts.put(peer, current + 1);
    totalUnreadDMs++;
    updateDMTabBadge();
    refreshPeersList();
}

private void clearUnreadForPeer(String peer) {
    int cleared = unreadCounts.getOrDefault(peer, 0);
    if (cleared > 0) {
        unreadCounts.put(peer, 0);
        totalUnreadDMs = Math.max(0, totalUnreadDMs - cleared);
        updateDMTabBadge();
        refreshPeersList();
    }
}

    
    private void updateDMTabBadge() {
        int dmIndex = tabs.indexOfTab("DMs");
        if (dmIndex < 0) {
            // Try with existing badge
            dmIndex = tabs.indexOfTab("DMs (" + (totalUnreadDMs - 1) + ")");
            if (dmIndex < 0) {
                // Search for any DMs tab with badge pattern
                for (int i = 0; i < tabs.getTabCount(); i++) {
                    String title = tabs.getTitleAt(i);
                    if (title != null && title.startsWith("DMs")) {
                        dmIndex = i;
                        break;
                    }
                }
            }
        }
        
        if (dmIndex >= 0) {
            if (totalUnreadDMs > 0) {
                tabs.setTitleAt(dmIndex, "DMs (" + totalUnreadDMs + ")");
            } else {
                tabs.setTitleAt(dmIndex, "DMs");
            }
        }
    }
    
    private void refreshPeersList() {
        // Force the list to repaint with updated badges
        dmPeersList.repaint();
    }

    private static boolean contains(DefaultListModel<String> model, String s) {
        for (int i = 0; i < model.size(); i++) if (Objects.equals(model.get(i), s)) return true;
        return false;
    }

    // ===== Misc =====
    private void toast(String s) {
        appendGeneralSystem("INFO", s);
        status.setText(s);
    }

    private void clearCurrentTab() {
        int idx = tabs.getSelectedIndex();
        if (idx == 0) {
            generalPanel.clear();
        } else {
            String peer = dmPeersList.getSelectedValue();
            if (peer != null) {
                BubblePanel area = dmThreads.get(peer);
                if (area != null) area.clear();
            }
        }
    }

    private String tsNow() {
        return LocalTime.now().format(TS);
    }

    private void close() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
        in = null;
        out = null;
        status.setText("Disconnected");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ChatSwingApp().setVisible(true));
    }

    // ======= Bubble UI components =======
    private static class BubblePanel extends JPanel {
        private final JPanel inner;

        BubblePanel() {
            super(new BorderLayout());
            setBackground(SURFACE);
            inner = new JPanel();
            inner.setBackground(SURFACE);
            inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
            add(inner, BorderLayout.NORTH);
        }

        void addBubble(String from, String meta, String text, boolean self, String timestamp) {
            Bubble b = new Bubble(from, meta, text, self, timestamp);
            JPanel row = new JPanel(new BorderLayout());
            row.setOpaque(false);
            if (self) {
                row.add(b, BorderLayout.EAST);
            } else {
                row.add(b, BorderLayout.WEST);
            }
            inner.add(row);
            inner.add(Box.createVerticalStrut(6));
            revalidate();
            repaint();
        }



        void addSystem(String text) {
            JLabel l = new JLabel(text);
            l.setForeground(ON_MUTE);
            JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 2));
            row.setOpaque(false);
            row.add(l);
            inner.add(row);
            inner.add(Box.createVerticalStrut(6));
            revalidate();
            repaint();
        }

        void clear() {
            inner.removeAll();
            revalidate();
            repaint();
        }

        void scrollToBottom() {
            Container parent = getParent();
            while (parent != null && !(parent instanceof JViewport)) parent = parent.getParent();
            if (parent instanceof JViewport) {
                SwingUtilities.invokeLater(() -> {
                    Rectangle r = new Rectangle(0, inner.getHeight() - 1, 1, 1);
                    inner.scrollRectToVisible(r);
                });
            }
        }
    }

    private static class Bubble extends JPanel {
            Bubble(String from, String meta, String text, boolean self, String timestamp) {
        setOpaque(false);
        setLayout(new BorderLayout(8, 4));

        // bubble card
        JPanel card = new JPanel(new BorderLayout(0, 6)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(self ? ME_BUBBLE : OTHER_BUB);
                int arc = 18;
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        card.setOpaque(false);
        card.setBorder(new EmptyBorder(10, 12, 10, 12));

        JLabel metaL = new JLabel(meta);
        metaL.setForeground(new Color(255, 255, 255, 180));
        metaL.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        JLabel textL = new JLabel(htmlWrap(text));
        textL.setForeground(Color.WHITE);
        textL.setFont(new Font("Segoe UI", Font.PLAIN, 14));

        card.add(metaL, BorderLayout.NORTH);
        card.add(textL, BorderLayout.CENTER);

        add(card, BorderLayout.CENTER);
        setMaximumSize(new Dimension(620, Integer.MAX_VALUE)); // wrap width
    }


        private String htmlWrap(String s) {
            // Basic HTML wrap to allow automatic line breaks and preserve spaces
            String esc = s
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\n", "<br>");
            return "<html><body style='width:580px'>" + esc + "</body></html>";
        }
    }

    private static class BubbleScroll extends JScrollPane {
        BubbleScroll(Component c) {
            super(c);
            getViewport().setBackground(SURFACE);
            setBorder(new LineBorder(BORDER));
            getVerticalScrollBar().setUnitIncrement(24);
        }
    }

    private static class UserCell extends JLabel implements ListCellRenderer<String> {
        UserCell() {
            setOpaque(true);
            setFont(new Font("Segoe UI", Font.PLAIN, 14));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends String> list, String value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            setText("  ●  " + value);
            if (isSelected) {
                setBackground(new Color(0x29434E));
                setForeground(ON_BG);
            } else {
                setBackground((index % 2 == 0) ? SURFACE : SURFACE2);
                setForeground(new Color(0x81C784)); // online green-ish
            }
            return this;
        }
    }

    private static class PeerCell extends JLabel implements ListCellRenderer<String> {
        private ChatSwingApp parent;
        
        PeerCell() {
            setOpaque(true);
            setFont(new Font("Segoe UI", Font.PLAIN, 14));
        }
        
        void setParent(ChatSwingApp parent) {
            this.parent = parent;
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends String> list, String value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            // Get unread count for this peer
            int unread = 0;
            if (parent != null && parent.unreadCounts.containsKey(value)) {
                unread = parent.unreadCounts.get(value);
            }
            
            // Display peer name with badge if unread
            if (unread > 0) {
                setText("  " + value + " (" + unread + ")");
                setFont(getFont().deriveFont(Font.BOLD));
            } else {
                setText("  " + value);
                setFont(new Font("Segoe UI", Font.PLAIN, 14));
            }
            
            if (isSelected) {
                setBackground(new Color(0x263238));
                setForeground(ON_BG);
            } else {
                setBackground((index % 2 == 0) ? SURFACE : SURFACE2);
                if (unread > 0) {
                    setForeground(PRIMARY); // Highlight unread conversations
                } else {
                    setForeground(ON_BG);
                }
            }
            return this;
        }
    }
}