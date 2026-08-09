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

import rotp.model.empires.Empire;
import rotp.model.galaxy.StarSystem;
import rotp.model.game.GameSession;

public class BasesDestroyedAlert extends GameAlert {
    private final Empire spy;
    private final int count;
    private final StarSystem system;
    public static BasesDestroyedAlert create(Empire e, int num, StarSystem sys) {
        BasesDestroyedAlert a = new BasesDestroyedAlert(e,num,sys);
        GameSession.instance().addAlert(a);
        return a;
    }
    @Override
    public String description() {
        String desc;
        if (spy == null)
            desc = text("MAIN_ALERT_BASES_DESTROYED2", str(count), recipient().sv.name(system.id));
        else {
            desc = text("MAIN_ALERT_BASES_DESTROYED", str(count), recipient().sv.name(system.id));
            desc = spy.replaceTokens(desc, "alien");
        }
        return desc;
    }
    private BasesDestroyedAlert(Empire e, int num, StarSystem sys) {
        spy = e;
        count = num;
        system = sys;
    }
}
