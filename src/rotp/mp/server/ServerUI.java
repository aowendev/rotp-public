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

import java.util.ArrayList;
import java.util.List;
import rotp.model.game.SessionUI;
import rotp.ui.notifications.TurnNotification;

/**
 * Headless SessionUI for the multiplayer server. Turn notifications are
 * collected instead of shown; per-empire routing to remote clients comes
 * in a later phase. All panel/prompt operations are inherited no-ops.
 */
public class ServerUI implements SessionUI {
    private final List<TurnNotification> collected = new ArrayList<>();
    private volatile boolean gameOver = false;

    @Override
    public void processNotifications(List<TurnNotification> notifications) {
        synchronized (collected) {
            collected.addAll(notifications);
        }
        for (TurnNotification tn : notifications)
            System.out.println("[server] turn notification: " + tn.getClass().getSimpleName());
    }
    @Override
    public void selectGameOverPanel() { gameOver = true; }

    public boolean gameOver() { return gameOver; }

    /** drain notifications collected during the last turn */
    public List<TurnNotification> drainNotifications() {
        synchronized (collected) {
            List<TurnNotification> out = new ArrayList<>(collected);
            collected.clear();
            return out;
        }
    }
}
