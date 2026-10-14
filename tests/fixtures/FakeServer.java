/*
 * Block Shipper - run and manage Minecraft servers from a web dashboard.
 * Copyright (C) 2025-2026 zauberen
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

// A stand-in for a minecraft server used by the tests. It prints vanilla style log
// lines, accepts console commands on stdin and echoes bytes on its server-port.
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

public class FakeServer {
    static synchronized void log(String msg) {
        System.out.println("[12:00:00] [Server thread/INFO]: " + msg);
        System.out.flush();
    }

    public static void main(String[] args) throws Exception {
        Properties props = new Properties();
        Path propsPath = Paths.get("server.properties");
        if (Files.exists(propsPath)) {
            try (Reader r = Files.newBufferedReader(propsPath)) { props.load(r); }
        }
        int port = Integer.parseInt(props.getProperty("server-port", "25565"));
        long delay = Long.parseLong(System.getenv().getOrDefault("FAKE_DELAY", "200"));
        log("Starting minecraft server version fake");
        Thread.sleep(delay);
        ServerSocket ss = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
        Thread acceptor = new Thread(() -> {
            while (true) {
                try {
                    Socket s = ss.accept();
                    new Thread(() -> {
                        try (Socket c = s) {
                            InputStream in = c.getInputStream();
                            OutputStream out = c.getOutputStream();
                            byte[] buf = new byte[4096];
                            int n;
                            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); out.flush(); }
                        } catch (IOException ignored) { }
                    }).start();
                } catch (IOException e) { return; }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
        log("Done (0.123s)! For help, type \"help\"");
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = stdin.readLine()) != null) {
            String[] parts = line.trim().split(" ", 2);
            switch (parts[0]) {
                case "stop":
                    log("Stopping the server");
                    ss.close();
                    System.exit(0);
                case "crash":
                    System.exit(3);
                case "join":
                    log(parts[1] + " joined the game");
                    break;
                case "leave":
                    log(parts[1] + " left the game");
                    break;
                case "save-all":
                    log("Saved the game");
                    break;
                default:
                    log("Ran: " + line);
            }
        }
    }
}
