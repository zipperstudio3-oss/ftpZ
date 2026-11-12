package com.ftpz.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.Channels;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A small, dependency-free FTP server (RFC 959 + common extensions).
 * Pure Java: contains no Android classes, so it can be tested on any JVM.
 */
public class FtpServer {

    public interface Listener {
        void onLog(String message);

        void onClientsChanged(int count);
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_CLIENTS = 12;
    private static final int IDLE_TIMEOUT_MS = 10 * 60 * 1000;
    private static final int DATA_TIMEOUT_MS = 30 * 1000;

    private final File root;
    private final String rootCanonical;
    private final int port;
    private final String user;
    private final String pass;
    private final boolean anonymous;
    private final boolean readOnly;
    private final Listener listener;

    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private final AtomicLong bytesSent = new AtomicLong();
    private final AtomicLong bytesReceived = new AtomicLong();
    private ExecutorService pool;
    private ServerSocket serverSocket;
    private volatile boolean running;

    public FtpServer(File root, int port, String user, String pass,
                     boolean anonymous, boolean readOnly, Listener listener) throws IOException {
        this.root = root.getCanonicalFile();
        this.rootCanonical = this.root.getPath();
        this.port = port;
        this.user = user == null ? "" : user;
        this.pass = pass == null ? "" : pass;
        this.anonymous = anonymous;
        this.readOnly = readOnly;
        this.listener = listener;
    }

    // ------------------------------------------------------------------ lifecycle

    public synchronized void start() throws IOException {
        if (running) return;
        ServerSocket ss = new ServerSocket();
        try {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            closeQuietly(ss);
            throw e;
        }
        serverSocket = ss;
        pool = Executors.newCachedThreadPool();
        running = true;
        Thread acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "ftpz-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        log("Server started on port " + ss.getLocalPort());
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        for (Session s : sessions) s.shutdown();
        sessions.clear();
        if (pool != null) pool.shutdownNow();
        notifyClients();
        log("Server stopped");
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : port;
    }

    public int getClientCount() {
        return sessions.size();
    }

    public long getBytesSent() {
        return bytesSent.get();
    }

    public long getBytesReceived() {
        return bytesReceived.get();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                if (sessions.size() >= MAX_CLIENTS) {
                    try {
                        client.getOutputStream().write("421 Too many connections\r\n".getBytes(UTF8));
                    } catch (IOException ignored) {
                    }
                    closeQuietly(client);
                    continue;
                }
                Session s = new Session(client);
                sessions.add(s);
                notifyClients();
                pool.execute(s);
            } catch (IOException e) {
                if (running) log("✕ Accept error: " + e.getMessage());
                if (!running) break;
            }
        }
    }

    private void log(String msg) {
        if (listener != null) listener.onLog(msg);
    }

    private void notifyClients() {
        if (listener != null) listener.onClientsChanged(sessions.size());
    }

    private static void closeQuietly(Socket s) {
        try {
            if (s != null) s.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(ServerSocket s) {
        try {
            if (s != null) s.close();
        } catch (IOException ignored) {
        }
    }

    static String formatSize(long b) {
        if (b < 1024) return b + " B";
        double v = b / 1024.0;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.US, "%.1f %s", v, u[i]);
    }

    // ------------------------------------------------------------------ session

    private final class Session implements Runnable {
        private final Socket control;
        private final String peer;
        private InputStream in;
        private OutputStream out;

        private boolean authed;
        private String pendingUser;
        private String cwd = "/";
        private String renameFrom;
        private long restart;
        private boolean closing;

        private ServerSocket passive;
        private InetAddress activeAddr;
        private int activePort;

        Session(Socket control) {
            this.control = control;
            this.peer = control.getInetAddress().getHostAddress();
        }

        void shutdown() {
            closing = true;
            closeQuietly(control);
            closeQuietly(passive);
        }

        @Override
        public void run() {
            log("+ " + peer + " connected");
            try {
                control.setSoTimeout(IDLE_TIMEOUT_MS);
                control.setTcpNoDelay(true);
                in = control.getInputStream();
                out = control.getOutputStream();
                reply("220 ftpZ ready");
                while (!closing) {
                    String line = readLine();
                    if (line == null) break;
                    if (line.length() == 0) continue;
                    handle(line);
                }
            } catch (SocketTimeoutException e) {
                try {
                    reply("421 Idle timeout");
                } catch (IOException ignored) {
                }
            } catch (IOException ignored) {
                // client went away
            } finally {
                closeQuietly(passive);
                closeQuietly(control);
                sessions.remove(this);
                notifyClients();
                log("• " + peer + " disconnected");
            }
        }

        /** Reads one CRLF/LF terminated line (UTF-8), limited to 8 KB. */
        private String readLine() throws IOException {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(128);
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') {
                    byte[] b = buf.toByteArray();
                    int len = b.length;
                    if (len > 0 && b[len - 1] == '\r') len--;
                    return new String(b, 0, len, UTF8);
                }
                if (buf.size() > 8192) throw new IOException("line too long");
                buf.write(c);
            }
            return null;
        }

        private synchronized void reply(String line) throws IOException {
            out.write((line + "\r\n").getBytes(UTF8));
            out.flush();
        }

        private void reply(int code, String text) throws IOException {
            reply(code + " " + text);
        }

        // -------------------------------------------------------------- commands

        private void handle(String line) throws IOException {
            String cmd;
            String arg;
            int sp = line.indexOf(' ');
            if (sp < 0) {
                cmd = line;
                arg = "";
            } else {
                cmd = line.substring(0, sp);
                arg = line.substring(sp + 1);
            }
            cmd = cmd.toUpperCase(Locale.US);

            // commands allowed before login
            switch (cmd) {
                case "USER":
                    cmdUser(arg);
                    return;
                case "PASS":
                    cmdPass(arg);
                    return;
                case "QUIT":
                    reply(221, "Goodbye");
                    closing = true;
                    return;
                case "NOOP":
                    reply(200, "OK");
                    return;
                case "SYST":
                    reply(215, "UNIX Type: L8");
                    return;
                case "FEAT":
                    reply("211-Features:\r\n UTF8\r\n SIZE\r\n MDTM\r\n REST STREAM\r\n EPSV\r\n"
                            + " MLST type*;size*;modify*;\r\n211 End");
                    return;
                case "OPTS":
                    reply(200, "OK");
                    return;
                case "AUTH":
                case "PBSZ":
                case "PROT":
                    reply(502, "TLS not supported");
                    return;
                default:
                    break;
            }

            if (!authed) {
                reply(530, "Please log in first");
                return;
            }

            switch (cmd) {
                case "PWD":
                case "XPWD":
                    reply("257 \"" + cwd.replace("\"", "\"\"") + "\" is the current directory");
                    break;
                case "CWD":
                case "XCWD":
                    cmdCwd(arg);
                    break;
                case "CDUP":
                case "XCUP":
                    cmdCwd("..");
                    break;
                case "TYPE":
                case "MODE":
                case "STRU":
                    reply(200, "OK");
                    break;
                case "PASV":
                    cmdPasv(false);
                    break;
                case "EPSV":
                    cmdPasv(true);
                    break;
                case "PORT":
                    cmdPort(arg);
                    break;
                case "EPRT":
                    cmdEprt(arg);
                    break;
                case "LIST":
                    cmdList(arg, true);
                    break;
                case "NLST":
                    cmdList(arg, false);
                    break;
                case "MLSD":
                    cmdMlsd(arg);
                    break;
                case "MLST":
                    cmdMlst(arg);
                    break;
                case "RETR":
                    cmdRetr(arg);
                    break;
                case "STOR":
                    cmdStor(arg, false);
                    break;
                case "APPE":
                    cmdStor(arg, true);
                    break;
                case "STOU":
                    cmdStor("ftpz_" + System.currentTimeMillis(), false);
                    break;
                case "DELE":
                    cmdDele(arg);
                    break;
                case "MKD":
                case "XMKD":
                    cmdMkd(arg);
                    break;
                case "RMD":
                case "XRMD":
                    cmdRmd(arg);
                    break;
                case "RNFR":
                    cmdRnfr(arg);
                    break;
                case "RNTO":
                    cmdRnto(arg);
                    break;
                case "SIZE":
                    cmdSize(arg);
                    break;
                case "MDTM":
                    cmdMdtm(arg);
                    break;
                case "REST":
                    cmdRest(arg);
                    break;
                case "ABOR":
                    reply(226, "Nothing to abort");
                    break;
                case "ALLO":
                    reply(202, "Not needed");
                    break;
                case "SITE":
                    reply(200, "OK");
                    break;
                case "STAT":
                    reply(211, "ftpZ server OK");
                    break;
                case "HELP":
                    reply(214, "ftpZ - simple FTP server");
                    break;
                default:
                    reply(502, "Command not implemented");
            }
        }

        private void cmdUser(String arg) throws IOException {
            authed = false;
            pendingUser = arg;
            if (anonymous) reply(331, "Send any password (anonymous access)");
            else reply(331, "Password required");
        }

        private void cmdPass(String arg) throws IOException {
            if (pendingUser == null) {
                reply(503, "Login with USER first");
                return;
            }
            boolean ok = anonymous || (pendingUser.equals(user) && arg.equals(pass));
            if (ok) {
                authed = true;
                log("✓ Login: " + (anonymous ? "anonymous" : pendingUser) + " (" + peer + ")");
                reply(230, "Logged in");
            } else {
                log("✕ Login failed from " + peer);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
                reply(530, "Login incorrect");
            }
        }

        private void cmdCwd(String arg) throws IOException {
            String v = virtual(arg);
            File f = real(v);
            if (f != null && f.isDirectory()) {
                cwd = v;
                reply(250, "Directory changed to " + v);
            } else {
                reply(550, "No such directory");
            }
        }

        private void cmdRest(String arg) throws IOException {
            try {
                restart = Math.max(0, Long.parseLong(arg.trim()));
                reply(350, "Restarting at " + restart);
            } catch (NumberFormatException e) {
                restart = 0;
                reply(501, "Bad offset");
            }
        }

        private void cmdSize(String arg) throws IOException {
            File f = real(virtual(arg));
            if (f != null && f.isFile()) reply(213, String.valueOf(f.length()));
            else reply(550, "Not a file");
        }

        private void cmdMdtm(String arg) throws IOException {
            File f = real(virtual(arg));
            if (f != null && f.exists()) reply(213, mdtm(f.lastModified()));
            else reply(550, "File not found");
        }

        private boolean denyIfReadOnly() throws IOException {
            if (readOnly) {
                reply(550, "Server is in read-only mode");
                return true;
            }
            return false;
        }

        private void cmdDele(String arg) throws IOException {
            if (denyIfReadOnly()) return;
            File f = real(virtual(arg));
            if (f != null && f.isFile() && f.delete()) {
                log("✕ Deleted " + f.getName());
                reply(250, "Deleted");
            } else reply(550, "Delete failed");
        }

        private void cmdMkd(String arg) throws IOException {
            if (denyIfReadOnly()) return;
            String v = virtual(arg);
            File f = real(v);
            if (f != null && !f.exists() && f.mkdirs()) {
                log("+ Folder " + f.getName());
                reply("257 \"" + v.replace("\"", "\"\"") + "\" created");
            } else reply(550, "Cannot create directory");
        }

        private void cmdRmd(String arg) throws IOException {
            if (denyIfReadOnly()) return;
            String v = virtual(arg);
            File f = real(v);
            if (f != null && !v.equals("/") && f.isDirectory() && f.delete()) {
                log("✕ Removed folder " + f.getName());
                reply(250, "Directory removed");
            } else reply(550, "Cannot remove directory (not empty?)");
        }

        private void cmdRnfr(String arg) throws IOException {
            if (denyIfReadOnly()) return;
            String v = virtual(arg);
            File f = real(v);
            if (f != null && !v.equals("/") && f.exists()) {
                renameFrom = v;
                reply(350, "Ready for RNTO");
            } else reply(550, "File not found");
        }

        private void cmdRnto(String arg) throws IOException {
            if (denyIfReadOnly()) return;
            if (renameFrom == null) {
                reply(503, "RNFR required first");
                return;
            }
            File from = real(renameFrom);
            File to = real(virtual(arg));
            renameFrom = null;
            if (from != null && to != null && !to.exists() && from.renameTo(to)) {
                log("• Renamed " + from.getName() + " → " + to.getName());
                reply(250, "Renamed");
            } else reply(550, "Rename failed");
        }

        // -------------------------------------------------------------- data connection

        private void cmdPasv(boolean extended) throws IOException {
            closeData();
            try {
                passive = new ServerSocket(0);
                passive.setSoTimeout(DATA_TIMEOUT_MS);
            } catch (IOException e) {
                reply(425, "Cannot open passive port");
                return;
            }
            int p = passive.getLocalPort();
            if (extended) {
                reply("229 Entering Extended Passive Mode (|||" + p + "|)");
                return;
            }
            InetAddress local = control.getLocalAddress();
            byte[] a = local instanceof Inet4Address ? local.getAddress() : null;
            if (a == null) {
                closeData();
                reply(425, "Use EPSV for IPv6");
                return;
            }
            reply("227 Entering Passive Mode (" + (a[0] & 255) + "," + (a[1] & 255) + ","
                    + (a[2] & 255) + "," + (a[3] & 255) + "," + (p >> 8) + "," + (p & 255) + ")");
        }

        private void cmdPort(String arg) throws IOException {
            String[] t = arg.trim().split(",");
            if (t.length != 6) {
                reply(501, "Bad PORT syntax");
                return;
            }
            try {
                int[] n = new int[6];
                for (int i = 0; i < 6; i++) n[i] = Integer.parseInt(t[i].trim());
                InetAddress addr = InetAddress.getByName(n[0] + "." + n[1] + "." + n[2] + "." + n[3]);
                setActive(addr, n[4] * 256 + n[5]);
            } catch (Exception e) {
                reply(501, "Bad PORT syntax");
            }
        }

        private void cmdEprt(String arg) throws IOException {
            // |proto|address|port|
            try {
                String d = arg.substring(0, 1);
                String[] t = arg.split(java.util.regex.Pattern.quote(d));
                setActive(InetAddress.getByName(t[2]), Integer.parseInt(t[3]));
            } catch (Exception e) {
                reply(501, "Bad EPRT syntax");
            }
        }

        private void setActive(InetAddress addr, int p) throws IOException {
            // Refuse "FTP bounce": the data target must be the client itself.
            if (!addr.equals(control.getInetAddress()) || p < 1 || p > 65535) {
                reply(501, "Illegal PORT command");
                return;
            }
            closeData();
            activeAddr = addr;
            activePort = p;
            reply(200, "PORT command successful");
        }

        private void closeData() {
            closeQuietly(passive);
            passive = null;
            activeAddr = null;
        }

        /** Opens the data connection prepared by PASV/EPSV/PORT/EPRT. */
        private Socket openData() {
            try {
                Socket s;
                if (passive != null) {
                    s = passive.accept();
                } else if (activeAddr != null) {
                    s = new Socket();
                    s.connect(new InetSocketAddress(activeAddr, activePort), DATA_TIMEOUT_MS);
                } else {
                    return null;
                }
                s.setSoTimeout(DATA_TIMEOUT_MS);
                return s;
            } catch (IOException e) {
                return null;
            } finally {
                closeQuietly(passive);
                passive = null;
                activeAddr = null;
            }
        }

        // -------------------------------------------------------------- listing

        private void cmdList(String arg, boolean longFormat) throws IOException {
            // ignore option flags such as "-a" or "-la"
            String path = arg;
            while (path.startsWith("-")) {
                int sp = path.indexOf(' ');
                path = sp < 0 ? "" : path.substring(sp + 1);
            }
            File target = real(virtual(path));
            if (target == null || !target.exists()) {
                reply(550, "No such file or directory");
                return;
            }
            File[] files = target.isDirectory() ? sorted(target) : new File[]{target};
            StringBuilder sb = new StringBuilder();
            for (File f : files) {
                if (longFormat) sb.append(unixLine(f));
                else sb.append(f.getName());
                sb.append("\r\n");
            }
            sendText(sb.toString());
        }

        private void cmdMlsd(String arg) throws IOException {
            File dir = real(virtual(arg));
            if (dir == null || !dir.isDirectory()) {
                reply(550, "Not a directory");
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (File f : sorted(dir)) sb.append(mlFacts(f)).append(' ').append(f.getName()).append("\r\n");
            sendText(sb.toString());
        }

        private void cmdMlst(String arg) throws IOException {
            String v = virtual(arg);
            File f = real(v);
            if (f == null || !f.exists()) {
                reply(550, "Not found");
                return;
            }
            reply("250-Listing " + v + "\r\n " + mlFacts(f) + " " + v + "\r\n250 End");
        }

        private void sendText(String text) throws IOException {
            if (passive == null && activeAddr == null) {
                reply(425, "Use PASV or PORT first");
                return;
            }
            reply(150, "Opening data connection");
            Socket d = openData();
            if (d == null) {
                reply(425, "Cannot open data connection");
                return;
            }
            try {
                OutputStream o = d.getOutputStream();
                o.write(text.getBytes(UTF8));
                o.flush();
                d.close();
                reply(226, "Transfer complete");
            } catch (IOException e) {
                closeQuietly(d);
                reply(426, "Transfer aborted");
            }
        }

        // -------------------------------------------------------------- transfers

        private void cmdRetr(String arg) throws IOException {
            File f = real(virtual(arg));
            long offset = restart;
            restart = 0;
            if (f == null || !f.isFile() || !f.canRead()) {
                reply(550, "File not found");
                return;
            }
            if (passive == null && activeAddr == null) {
                reply(425, "Use PASV or PORT first");
                return;
            }
            reply(150, "Opening data connection for " + f.getName());
            Socket d = openData();
            if (d == null) {
                reply(425, "Cannot open data connection");
                return;
            }
            long total = 0;
            try (FileInputStream fis = new FileInputStream(f)) {
                if (offset > 0) fis.getChannel().position(offset);
                OutputStream o = d.getOutputStream();
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = fis.read(buf)) > 0) {
                    o.write(buf, 0, n);
                    total += n;
                    bytesSent.addAndGet(n);
                }
                o.flush();
                d.close();
                log("↓ " + f.getName() + " (" + formatSize(total) + ") → " + peer);
                reply(226, "Transfer complete");
            } catch (IOException e) {
                closeQuietly(d);
                log("✕ Download interrupted: " + f.getName());
                reply(426, "Transfer aborted");
            }
        }

        private void cmdStor(String arg, boolean append) throws IOException {
            if (denyIfReadOnly()) return;
            File f = real(virtual(arg));
            long offset = restart;
            restart = 0;
            if (f == null || f.isDirectory() || f.getParentFile() == null || !f.getParentFile().isDirectory()) {
                reply(550, "Cannot write there");
                return;
            }
            if (passive == null && activeAddr == null) {
                reply(425, "Use PASV or PORT first");
                return;
            }
            reply(150, "Ready to receive " + f.getName());
            Socket d = openData();
            if (d == null) {
                reply(425, "Cannot open data connection");
                return;
            }
            long total = 0;
            OutputStream fo = null;
            try {
                if (offset > 0) {
                    RandomAccessFile raf = new RandomAccessFile(f, "rw");
                    raf.getChannel().position(offset);
                    fo = Channels.newOutputStream(raf.getChannel());
                } else {
                    fo = new FileOutputStream(f, append);
                }
                InputStream i = d.getInputStream();
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = i.read(buf)) > 0) {
                    fo.write(buf, 0, n);
                    total += n;
                    bytesReceived.addAndGet(n);
                }
                fo.close();
                fo = null;
                d.close();
                log("↑ " + f.getName() + " (" + formatSize(total) + ") ← " + peer);
                reply(226, "Transfer complete");
            } catch (IOException e) {
                try {
                    if (fo != null) fo.close();
                } catch (IOException ignored) {
                }
                closeQuietly(d);
                log("✕ Upload interrupted: " + f.getName());
                reply(426, "Transfer aborted");
            }
        }

        // -------------------------------------------------------------- path handling

        /** Resolves a client path to a normalised virtual path that never leaves "/". */
        private String virtual(String arg) {
            String p = arg == null ? "" : arg;
            String base = p.startsWith("/") ? "" : cwd;
            ArrayList<String> parts = new ArrayList<String>();
            for (String s : (base + "/" + p).split("/")) {
                if (s.length() == 0 || s.equals(".")) continue;
                if (s.equals("..")) {
                    if (!parts.isEmpty()) parts.remove(parts.size() - 1);
                } else parts.add(s);
            }
            StringBuilder sb = new StringBuilder();
            for (String s : parts) sb.append('/').append(s);
            return sb.length() == 0 ? "/" : sb.toString();
        }

        /** Maps a virtual path to a real file, or null if it would escape the root. */
        private File real(String vpath) {
            try {
                File f = new File(root, vpath.substring(1)).getCanonicalFile();
                String c = f.getPath();
                if (c.equals(rootCanonical) || c.startsWith(rootCanonical + File.separator)) return f;
            } catch (IOException ignored) {
            }
            return null;
        }

        private File[] sorted(File dir) {
            File[] files = dir.listFiles();
            if (files == null) return new File[0];
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            return files;
        }
    }

    // ------------------------------------------------------------------ formatting

    private static String unixLine(File f) {
        long now = System.currentTimeMillis();
        long m = f.lastModified();
        boolean recent = Math.abs(now - m) < 180L * 24 * 3600 * 1000;
        SimpleDateFormat fmt = new SimpleDateFormat(recent ? "MMM dd HH:mm" : "MMM dd  yyyy", Locale.US);
        String perms = f.isDirectory()
                ? (f.canWrite() ? "drwxr-xr-x" : "dr-xr-xr-x")
                : (f.canWrite() ? "-rw-r--r--" : "-r--r--r--");
        return String.format(Locale.US, "%s   1 ftpz     ftpz %12d %s %s",
                perms, f.isDirectory() ? 4096L : f.length(), fmt.format(new Date(m)), f.getName());
    }

    private static String mlFacts(File f) {
        return "type=" + (f.isDirectory() ? "dir" : "file") + ";size=" + f.length()
                + ";modify=" + mdtm(f.lastModified()) + ";";
    }

    private static String mdtm(long millis) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date(millis));
    }
}
