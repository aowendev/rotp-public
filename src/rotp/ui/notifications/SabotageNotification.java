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
package rotp.ui.notifications;

import rotp.model.empires.SabotageMission;
import rotp.ui.RotPUI;

public class SabotageNotification implements TurnNotification {
    public static void addMission(SabotageMission mission, int sysId) {
        RotPUI.instance().selectSabotagePanel(mission, sysId);
    }

    // ---- multiplayer ----
    // A remote human picks their own sabotage. Single-player does it by blocking on a
    // modal panel above, which a headless server cannot do, so the mission is queued
    // for the server to raise as a prompt instead.

    private final SabotageMission mission;
    private final int sysId;

    public static SabotageNotification createDeferred(SabotageMission m, int systemId) {
        SabotageNotification n = new SabotageNotification(m, systemId);
        rotp.model.game.GameSession.instance().addTurnNotification(n);
        return n;
    }
    private SabotageNotification(SabotageMission m, int systemId) {
        mission = m;
        sysId = systemId;
    }
    public SabotageMission mission()  { return mission; }
    public int systemId()             { return sysId; }

    @Override
    public String displayOrder() { return SABOTAGE; }
    @Override
    public void notifyPlayer() { }   // multiplayer only; there is no local player to ask
}
