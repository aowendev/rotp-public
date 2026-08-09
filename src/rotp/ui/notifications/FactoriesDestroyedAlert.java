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

public class FactoriesDestroyedAlert extends GameAlert {
    private final Empire spy;
    private final int count;
    private final StarSystem system;
    public static FactoriesDestroyedAlert create(Empire e, int num, StarSystem sv) {
        FactoriesDestroyedAlert a = new FactoriesDestroyedAlert(e,num,sv);
        GameSession.instance().addAlert(a);
        return a;
    }
    @Override
    public String description() {
        String desc;
        if (spy == null)
            desc = text("MAIN_ALERT_FACTORIES_DESTROYED2", str(count), recipient().sv.name(system.id));
        else {
            desc = text("MAIN_ALERT_FACTORIES_DESTROYED", str(count), recipient().sv.name(system.id));
            desc = spy.replaceTokens(desc, "alien");
        }
        return desc;
    }
    private FactoriesDestroyedAlert(Empire e, int num, StarSystem sv) {
        spy = e;
        count = num;
        system = sv;
    }
}
