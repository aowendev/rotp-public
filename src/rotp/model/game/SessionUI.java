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
package rotp.model.game;

import java.util.List;
import rotp.ui.notifications.TurnNotification;

/**
 * The UI operations that game-session and turn processing depend on.
 *
 * The desktop game registers RotPUI; the multiplayer server registers a
 * headless implementation. All methods default to no-ops so headless
 * implementations only override what they need (notification delivery).
 */
public interface SessionUI {
    default void clearAdvice()                        { }
    default void saveMapState()                       { }
    default void restoreMapState()                    { }
    default void repaint()                            { }
    default void selectMainPanel()                    { }
    default void selectMainPanelLoadGame()            { }
    default void selectGameOverPanel()                { }
    default void allocateSystems()                    { }
    default void showDisplayPanel()                   { }
    default void showMemoryLowPrompt()                { }
    default void showAutosaveFailedPrompt(String err) { }
    default void checkMapInitialized()                { }
    default void processNotifications(List<TurnNotification> notifications) { }
    default void showError(Exception e)               { }

    public static SessionUI get()          { return Holder.get(); }
    public static void set(SessionUI ui)   { Holder.instance = ui; }

    class Holder {
        private static SessionUI instance;
        private static synchronized SessionUI get() {
            // the desktop UI is the default when nothing was registered;
            // loading RotPUI here triggers its asset-loading static block,
            // so a headless server must call SessionUI.set(...) first
            if (instance == null)
                instance = rotp.ui.RotPUI.instance();
            return instance;
        }
    }
}
