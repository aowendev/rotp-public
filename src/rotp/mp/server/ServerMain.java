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
 */
public class ServerMain {
    public static final int DEFAULT_PORT = 8777;

    public static void run(String[] args) {
        System.setProperty("java.awt.headless", "true");

        int port = intArg(args, "port", DEFAULT_PORT);
        int players = intArg(args, "players", 2);
        String size = galaxySize(stringArg(args, "size", null));

        // must be registered before anything touches the game session,
        // otherwise the default SessionUI would load the Swing UI
        SessionUI.set(new ServerUI());

        // mirror the data-loading part of RotPUI's static block (no sounds/images):
        // languages() lazily loads labels, race definitions and race lang files
        UserPreferences.load();
        TechLibrary.current();
        LanguageManager.current().selectedLanguageName();

        GameServer server = new GameServer(port, players, size);
        server.run();  // blocks
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
