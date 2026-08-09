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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import rotp.model.empires.Empire;
import rotp.model.game.GameSession;
import rotp.mp.MpTestSupport.Client;
import rotp.mp.MpTestSupport.Server;
import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;

/**
 * Planetary reserve, both directions (Phase 4). Out: transferReserve moves
 * banked BC to one of your colonies. In: setEmpireTax sets the empire-wide tax
 * rate that banks colony production into the reserve — ROTP has no per-planet
 * manual banking, so the tax rate *is* the "add to reserve" order.
 */
public class ReserveTest {

    @Test
    @Timeout(120)
    void reserveBCCanBeSpentOnAColony() throws Exception {
        Server server = MpTestSupport.startServer(1);
        Client alice = new Client(server.port, "Alice");
        try {
            PlayerView v = alice.awaitView();
            Empire emp = GameSession.instance().galaxy().empire(v.empireId);
            emp.addToTreasury(500);              // a new empire starts with none banked
            PlayerView.SystemDto home = MpTestSupport.ownColony(v);

            Messages.TransferReserve cmd = new Messages.TransferReserve();
            cmd.systemId = home.id;
            cmd.amount = 200;
            assertTrue(alice.order(cmd).ok, "transfer accepted");

            PlayerView after = alice.lastView;
            assertEquals(300, Math.round(after.reserve), "reserve debited");
            PlayerView.SystemDto homeAfter = MpTestSupport.system(after, home.id);
            assertEquals(200, Math.round(homeAfter.colony.reserveIncome),
                "the colony now holds the transferred BC");
        }
        finally {
            alice.close();
            server.stop();
        }
    }

    @Test
    @Timeout(120)
    void aTransferIsRejectedWithoutTheBCOrAColony() throws Exception {
        Server server = MpTestSupport.startServer(1);
        Client alice = new Client(server.port, "Alice");
        try {
            PlayerView v = alice.awaitView();
            PlayerView.SystemDto home = MpTestSupport.ownColony(v);

            Messages.TransferReserve tooMuch = new Messages.TransferReserve();
            tooMuch.systemId = home.id;
            tooMuch.amount = 1_000_000;
            assertFalse(alice.order(tooMuch).ok, "cannot spend more than the reserve holds");

            Empire emp = GameSession.instance().galaxy().empire(v.empireId);
            emp.addToTreasury(500);
            Messages.TransferReserve notMine = new Messages.TransferReserve();
            notMine.systemId = foreignOrEmptySystem(v, home.id);
            notMine.amount = 10;
            assertFalse(alice.order(notMine).ok, "cannot fund someone else's system");
        }
        finally {
            alice.close();
            server.stop();
        }
    }

    @Test
    @Timeout(180)
    void theEmpireTaxRateBanksColonyProductionIntoTheReserve() throws Exception {
        Server server = MpTestSupport.startServer(1);
        Client alice = new Client(server.port, "Alice");
        try {
            PlayerView v = alice.awaitView();
            assertEquals(0, v.empireTaxLevel, "taxes start off");
            assertTrue(v.maxEmpireTaxLevel > 0, "the engine's ceiling is reported");

            Messages.SetEmpireTax tax = new Messages.SetEmpireTax();
            tax.level = v.maxEmpireTaxLevel;
            tax.onlyDeveloped = false;          // tax every colony, developed or not
            assertTrue(alice.order(tax).ok, "tax rate accepted");

            PlayerView set = alice.lastView;
            assertEquals(v.maxEmpireTaxLevel, set.empireTaxLevel, "rate echoed in the view");
            assertFalse(set.empireTaxOnlyDeveloped, "developed-only flag echoed");
            assertTrue(set.empireTaxRevenue > 0, "the rate banks BC each turn");

            float before = set.reserve;
            PlayerView after = alice.ready();
            assertTrue(after.reserve > before,
                "reserve grew from taxed production ("+before+" -> "+after.reserve+")");
        }
        finally {
            alice.close();
            server.stop();
        }
    }

    @Test
    @Timeout(120)
    void anOutOfRangeTaxRateIsRejected() throws Exception {
        Server server = MpTestSupport.startServer(1);
        Client alice = new Client(server.port, "Alice");
        try {
            PlayerView v = alice.awaitView();
            Messages.SetEmpireTax tax = new Messages.SetEmpireTax();
            tax.level = v.maxEmpireTaxLevel + 1;
            assertFalse(alice.order(tax).ok, "rate above the engine ceiling refused");
            assertEquals(0, alice.latestView().empireTaxLevel, "rate unchanged");
        }
        finally {
            alice.close();
            server.stop();
        }
    }

    /** a system this empire does not own — a valid id that must still be refused */
    private static int foreignOrEmptySystem(PlayerView v, int homeId) {
        for (PlayerView.SystemDto s : v.systems)
            if ((s.id != homeId) && (s.colony == null))
                return s.id;
        throw new IllegalStateException("no system other than the homeworld");
    }

    static { System.setProperty("java.awt.headless", "true"); }
}
