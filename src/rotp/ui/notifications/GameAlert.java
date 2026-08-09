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
import rotp.util.Base;

public abstract class GameAlert implements Base {
    // the empire this alert is addressed to (the victim/owner). Null means the local
    // player() — single-player and the desktop UI never set it, so behavior is unchanged.
    // The multiplayer server sets it so it can route the alert to the right human's client
    // and so description() frames the story from that empire's fog-of-war.
    private Empire recipient;

    /** the empire this alert is for; defaults to the local player() when unset */
    public Empire recipient()       { return recipient == null ? player() : recipient; }
    public GameAlert recipient(Empire e) { recipient = e; return this; }

    public abstract String description();
}
