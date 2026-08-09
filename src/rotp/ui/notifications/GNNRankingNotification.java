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

import java.util.List;
import rotp.model.empires.Empire;
import rotp.ui.RotPUI;

public class GNNRankingNotification implements TurnNotification, PublicNews {
    private final String type;
    private final String eventId;
    private final List<Empire> empires;

    public GNNRankingNotification(String messageType, List<Empire> empireList, String id) {
        type = messageType;
        empires = empireList;
        eventId = id;
    }
    @Override
    public String newsText() {
        // the title is already-resolved text; append the ranked empires (list is sorted
        // strongest-first, so index 0 is rank 1)
        StringBuilder sb = new StringBuilder(type == null ? "" : type);
        if (empires != null) {
            for (int i = 0; i < empires.size(); i++)
                sb.append(i == 0 ? "  " : ", ").append(i + 1).append(". ").append(empires.get(i).name());
        }
        return sb.toString();
    }
    @Override
    public String displayOrder() { return GNN_NOTIFY; }
    @Override
    public void notifyPlayer() {
        RotPUI.instance().selectGNNPanel(type, eventId, empires);
    }
}
