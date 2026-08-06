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
package rotp.mp.client;

import java.util.ArrayList;
import java.util.List;

import rotp.mp.protocol.PlayerView;
import rotp.mp.protocol.PlayerView.EmpireDto;

/**
 * Pure, rendering-independent diplomacy logic for the Races screen: which
 * actions are legal against a contacted empire right now, plus a readable
 * relationship label. Mirrors the server's validation in
 * GameServer.applyDiploOffer / applyBreakTreaty / applyDeclareWar so the panel
 * only offers actions the server will accept.
 *
 * Browser-client blueprint; unit-tested independent of Swing (mirrors
 * ColonyAllocations / FleetView / ShipDesigns).
 */
public final class Diplomacy {
    private Diplomacy() { }

    /** empires this player has contacted (everyone in the view except itself) */
    public static List<EmpireDto> contacted(PlayerView view) {
        List<EmpireDto> out = new ArrayList<>();
        if ((view == null) || (view.empires == null))
            return out;
        for (EmpireDto e : view.empires)
            if (e.id != view.empireId)
                out.add(e);
        return out;
    }

    // --- offers (server: applyDiploOffer) ---
    public static boolean canOfferTrade(EmpireDto e)    { return !e.atWar && (e.maxTradeLevel > 0); }
    public static boolean canOfferPeace(EmpireDto e)    { return e.atWar; }
    public static boolean canOfferPact(EmpireDto e)     { return !e.atWar && !e.pact; }
    public static boolean canOfferAlliance(EmpireDto e) { return !e.atWar && !e.alliance; }

    // --- war (server: applyDeclareWar rejects a duplicate declaration) ---
    public static boolean canDeclareWar(EmpireDto e)    { return !e.atWar; }

    // --- break treaty (server: applyBreakTreaty) ---
    public static boolean canBreakTrade(EmpireDto e)    { return e.tradeLevel > 0; }
    public static boolean canBreakPact(EmpireDto e)     { return e.pact; }
    public static boolean canBreakAlliance(EmpireDto e) { return e.alliance; }

    /** true if any treaty exists that could be unilaterally broken */
    public static boolean hasBreakableTreaty(EmpireDto e) {
        return canBreakTrade(e) || canBreakPact(e) || canBreakAlliance(e);
    }

    /** the treaties currently breakable against this empire (TRADE/PACT/ALLIANCE) */
    public static List<String> breakableTreaties(EmpireDto e) {
        List<String> out = new ArrayList<>();
        if (canBreakTrade(e))    out.add("TRADE");
        if (canBreakPact(e))     out.add("PACT");
        if (canBreakAlliance(e)) out.add("ALLIANCE");
        return out;
    }

    /** readable relationship summary, most-significant relation first */
    public static String statusLabel(EmpireDto e) {
        StringBuilder sb = new StringBuilder();
        if (e.atWar)         sb.append("At War");
        else if (e.alliance) sb.append("Alliance");
        else if (e.pact)     sb.append("Non-Aggression Pact");
        else if (e.atPeace)  sb.append("At Peace");
        else                 sb.append("Contact");
        if (e.tradeLevel > 0)
            sb.append(", Trade ").append(e.tradeLevel);
        if (e.spySpending > 0)
            sb.append(", Spies ").append(e.spies).append("/").append(e.maxSpies);
        return sb.toString();
    }

    /** display name for an empire entry, falling back to its id */
    public static String displayName(EmpireDto e) {
        String nm = ((e.name == null) || e.name.isEmpty()) ? ("Empire " + e.id) : e.name;
        String race = ((e.race == null) || e.race.isEmpty()) ? "" : " (" + e.race + ")";
        return nm + race;
    }
}
