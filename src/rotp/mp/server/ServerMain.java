/*
 * Copyright 2015-2020 Ray Fowler
 *
 * Licensed under the GNU General Public License, Version 3 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.gnu.org/licenses/gpl-3.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rotp.mp.server;

import rotp.model.game.SessionUI;
import rotp.model.tech.TechLibrary;
import rotp.ui.UserPreferences;
import rotp.util.LanguageManager;

/**
 * Headless multiplayer server entry point:
 *   java -jar rotp.jar --server [port=8777] [players=2]
 *
 * One JVM hosts one game. To run several games on a host, start one process per
 * game on its own port — see docs/deployment.md for the Oracle Cloud ARM setup
 * (systemd template unit + a TLS-terminating reverse proxy).
 *
 * Internet-facing arguments:
 *   bind=127.0.0.1        listen only on loopback (use behind a reverse proxy)
 *   keystore=/path.p12    serve wss:// directly from the JVM instead of proxying
 *   keystorePassword=...  password for that PKCS12 keystore
 *   savedir=/path         where this game writes saves (one dir per game)
 */
public class ServerMain {
    public static final int DEFAULT_PORT = 8777;

    public static void run(String[] args) {
        System.setProperty("java.awt.headless", "true");

        int port = intArg(args, "port", DEFAULT_PORT);
        int players = intArg(args, "players", 2);
        String size = galaxySize(stringArg(args, "size", null));
        String load = stringArg(args, "load", null);   // resume a saved game instead of a new one
        int turnTimer = intArg(args, "timer", 0);       // seconds per turn before auto-resolve; 0 = off
        String bind = stringArg(args, "bind", null);    // null = every interface
        String keystore = stringArg(args, "keystore", null);
        String keystorePassword = stringArg(args, "keystorepassword", "");

        // must be registered before anything touches the game session,
        // otherwise the default SessionUI would load the Swing UI
        SessionUI.set(new ServerUI());

        // mirror the data-loading part of RotPUI's static block (no sounds/images):
        // languages() lazily loads labels, race definitions and race lang files
        UserPreferences.load();
        // several games on one host share the jar, so each needs its own save dir
        String saveDir = stringArg(args, "savedir", null);
        if (saveDir != null) {
            new java.io.File(saveDir).mkdirs();
            UserPreferences.saveDirForThisProcess(saveDir);
            System.out.println("[server] saving games to "+saveDir);
        }
        TechLibrary.current();
        LanguageManager.current().selectedLanguageName();

        GameServer server = new GameServer(bind, port, players, size, load);
        server.setTurnTimer(turnTimer);
        if (keystore != null)
            enableTls(server, keystore, keystorePassword);
        server.run();  // blocks
    }

    /**
     * Serve wss:// straight from the JVM, for a deployment with no reverse proxy.
     * A browser page loaded over https cannot open a plain ws:// socket, so one of
     * the two (this or a proxy) is required for a hosted game.
     *
     * The proxy is usually the better trade: a renewed certificate is picked up by
     * reloading the proxy, whereas reloading it here means restarting the JVM —
     * which ends the game in progress. Use this when a game host wants a single
     * self-contained process.
     */
    private static void enableTls(GameServer server, String keystorePath, String password) {
        try {
            char[] pw = password.toCharArray();
            java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
            try (java.io.InputStream in = new java.io.FileInputStream(keystorePath)) {
                ks.load(in, pw);
            }
            javax.net.ssl.KeyManagerFactory kmf =
                javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pw);
            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            server.setWebSocketFactory(
                new org.java_websocket.server.DefaultSSLWebSocketServerFactory(ctx));
            System.out.println("[server] TLS enabled from "+keystorePath+"; clients connect with wss://");
        }
        catch (Exception e) {
            // refuse to fall back to plain ws: an operator who asked for TLS must
            // not silently get an unencrypted public port
            throw new IllegalStateException("could not enable TLS from keystore "+keystorePath, e);
        }
    }

    /** map a friendly size token (tiny/small/medium/large/huge) to an IGameOptions constant */
    static String galaxySize(String token) {
        if (token == null)
            return null;
        switch (token.toLowerCase()) {
            case "tiny":   return rotp.model.game.IGameOptions.SIZE_TINY;
            case "small":  return rotp.model.game.IGameOptions.SIZE_SMALL;
            case "medium": return rotp.model.game.IGameOptions.SIZE_MEDIUM;
            case "large":  return rotp.model.game.IGameOptions.SIZE_LARGE;
            case "huge":   return rotp.model.game.IGameOptions.SIZE_HUGE;
            default:
                System.out.println("Unknown galaxy size '"+token+"', using ruleset default");
                return null;
        }
    }

    public static int intArg(String[] args, String key, int defaultValue) {
        String prefix = key+"=";
        for (String arg : args) {
            if (arg.toLowerCase().startsWith(prefix)) {
                try { return Integer.parseInt(arg.substring(prefix.length())); }
                catch (NumberFormatException e) {
                    System.out.println("Ignoring invalid argument: "+arg);
                }
            }
        }
        return defaultValue;
    }

    public static String stringArg(String[] args, String key, String defaultValue) {
        String prefix = key+"=";
        for (String arg : args) {
            if (arg.toLowerCase().startsWith(prefix))
                return arg.substring(prefix.length());
        }
        return defaultValue;
    }
}
