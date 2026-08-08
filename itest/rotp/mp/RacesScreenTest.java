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
package rotp.mp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import rotp.mp.MpTestSupport.Client;
import rotp.mp.MpTestSupport.Server;
import rotp.mp.client.Diplomacy;
import rotp.mp.client.RacesPanel;
import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;
import rotp.mp.protocol.PlayerView.EmpireDto;

/**
 * The Races (diplomacy) screen: the pure {@link Diplomacy} helper decides which
 * actions are legal against a contacted empire (matching the server's
 * validation), and {@link RacesPanel} loads a view and renders a reply without
 * a display. The live diplomacy round-trip is covered by SpyDiplomacyTest.
 */
public class RacesScreenTest {

    private static EmpireDto empire(int id, String name) {
        EmpireDto e = new EmpireDto();
        e.id = id;
        e.name = name;
        return e;
    }

    @Test
    void contactedExcludesSelf() {
        PlayerView v = new PlayerView();
        v.empireId = 0;
        v.empires.add(empire(0, "You"));
        v.empires.add(empire(1, "Bulrathi"));
        v.empires.add(empire(2, "Psilon"));

        List<EmpireDto> others = Diplomacy.contacted(v);
        assertEquals(2, others.size(), "self is excluded from the contacted list");
        assertFalse(others.stream().anyMatch(e -> e.id == 0), "own empire not listed");
    }

    @Test
    void offerLegalityMatchesServerRules() {
        // fresh contact, no treaties, trade available: can offer everything except peace
        EmpireDto neutral = empire(1, "Neutral");
        neutral.maxTradeLevel = 100;
        assertTrue(Diplomacy.canOfferTrade(neutral), "trade offerable when a trade level is available");
        assertTrue(Diplomacy.canOfferPact(neutral), "pact offerable at peace");
        assertTrue(Diplomacy.canOfferAlliance(neutral), "alliance offerable at peace");
        assertTrue(Diplomacy.canDeclareWar(neutral), "war declarable when not already at war");
        assertFalse(Diplomacy.canOfferPeace(neutral), "peace only makes sense while at war");

        // at war: only peace is offerable; no trade/pact/alliance/duplicate-war
        EmpireDto enemy = empire(2, "Enemy");
        enemy.atWar = true;
        enemy.maxTradeLevel = 100;
        assertTrue(Diplomacy.canOfferPeace(enemy), "peace offerable while at war");
        assertFalse(Diplomacy.canOfferTrade(enemy), "no trade while at war");
        assertFalse(Diplomacy.canOfferPact(enemy), "no pact while at war");
        assertFalse(Diplomacy.canOfferAlliance(enemy), "no alliance while at war");
        assertFalse(Diplomacy.canDeclareWar(enemy), "already at war - cannot re-declare");

        // an existing pact cannot be re-offered but can be broken
        EmpireDto ally = empire(3, "Ally");
        ally.pact = true;
        assertFalse(Diplomacy.canOfferPact(ally), "pact already in effect");
        assertTrue(Diplomacy.canBreakPact(ally), "existing pact is breakable");
    }

    @Test
    void breakableTreatiesListsOnlyActiveOnes() {
        EmpireDto e = empire(1, "Partner");
        assertTrue(Diplomacy.breakableTreaties(e).isEmpty(), "nothing to break with no treaties");
        assertFalse(Diplomacy.hasBreakableTreaty(e), "no breakable treaty");

        e.tradeLevel = 50;
        e.alliance = true;
        List<String> breakable = Diplomacy.breakableTreaties(e);
        assertTrue(breakable.contains("TRADE"), "active trade route is breakable");
        assertTrue(breakable.contains("ALLIANCE"), "alliance is breakable");
        assertFalse(breakable.contains("PACT"), "no pact, so not breakable");
        assertTrue(Diplomacy.hasBreakableTreaty(e));
    }

    @Test
    void statusLabelReflectsRelation() {
        EmpireDto war = empire(1, "Enemy");
        war.atWar = true;
        assertTrue(Diplomacy.statusLabel(war).startsWith("At War"));

        EmpireDto trading = empire(2, "Trader");
        trading.pact = true;
        trading.tradeLevel = 75;
        String label = Diplomacy.statusLabel(trading);
        assertTrue(label.contains("Pact"), "pact shown");
        assertTrue(label.contains("Trade 75"), "trade level shown");
    }

    @Test
    void panelLoadsRacesFromViewAndShowsReply() {
        AtomicReference<Object> sent = new AtomicReference<>();
        RacesPanel panel = new RacesPanel(sent::set);

        PlayerView v = new PlayerView();
        v.empireId = 0;
        v.empires.add(empire(0, "You"));
        EmpireDto other = empire(1, "Bulrathi");
        other.maxTradeLevel = 100;
        other.personality = "Xenophobic";
        other.objective = "Expansionist";
        // spy + intel fields drive the spy controls and the report
        other.spySpending = 6;
        other.spyMission = "ESPIONAGE";
        other.spies = 2;
        other.maxSpies = 4;
        other.relativePower = 1.2f;
        other.knownTechCount = 7;
        other.reportAge = 3;
        v.empires.add(other);
        panel.updateFromView(v);   // headless: builds cards (incl. spy controls) without a display

        Messages.DiploReply dr = new Messages.DiploReply();
        dr.empireId = 1;
        dr.action = "PACT";
        dr.accepted = true;
        dr.text = "We accept your proposal.";
        panel.showReply(dr);       // must not throw resolving the empire name
        assertNotNull(panel);
    }

    @Test
    @Timeout(120)
    void serverReportsLeaderDisposition() throws Exception {
        Server server = MpTestSupport.startServer(1);
        Client alice = new Client(server.port, "Alice");
        try {
            PlayerView v = alice.awaitView();
            EmpireDto self = null;
            for (EmpireDto e : v.empires)
                if (e.id == v.empireId)
                    self = e;
            assertNotNull(self, "the view lists the player's own empire");
            assertNotNull(self.personality, "leader personality (disposition) is reported");
            assertNotNull(self.objective, "leader objective is reported");
        }
        finally {
            alice.close();
            server.stop();
        }
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
