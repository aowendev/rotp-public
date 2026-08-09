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

import rotp.model.empires.EspionageMission;
import rotp.model.game.GameSession;
import rotp.ui.RotPUI;

public class StealTechNotification implements TurnNotification {
    EspionageMission mission;
    int empId;
    private rotp.model.empires.Spy spy;      // multiplayer: needed to finish the mission later
    private boolean deferred;                // multiplayer: awaiting a remote human's choice

    public static void create(EspionageMission t, int empId) {
        GameSession.instance().addTurnNotification(new StealTechNotification(t, empId));
    }
    /**
     * Multiplayer: the espionage succeeded but the technology has not been chosen,
     * because the choice belongs to a remote human who cannot be asked mid-turn. The
     * server picks this up and raises a prompt; the spy is carried along so the
     * mission can be completed once they answer.
     */
    public static StealTechNotification createDeferred(EspionageMission t, int empId,
                                                       rotp.model.empires.Spy s) {
        StealTechNotification n = new StealTechNotification(t, empId);
        n.spy = s;
        n.deferred = true;
        GameSession.instance().addTurnNotification(n);
        return n;
    }
    public EspionageMission mission()          { return mission; }
    public int empireId()                      { return empId; }
    public rotp.model.empires.Spy spy()        { return spy; }
    public boolean deferred()                  { return deferred; }
    public StealTechNotification(EspionageMission t, int id) {
        mission = t;
        empId = id;
    }
    @Override
    public String displayOrder() { return STEAL_TECH; }
    @Override
    public void notifyPlayer() {
        if (deferred)
            return;      // multiplayer only; there is no local player to ask
        if (mission.hasStolenTech())
            RotPUI.instance().selectStealTechPanel(mission, empId);
        else
            RotPUI.instance().selectEspionageMissionPanel(mission, empId);
    }
}
