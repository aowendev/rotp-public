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

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;

import rotp.mp.protocol.Messages;
import rotp.mp.protocol.PlayerView;
import rotp.mp.protocol.PlayerView.EmpireDto;

/**
 * The Races (diplomacy) screen — the MOO-port "Races/Audience" surface, rendered
 * from PlayerView.empires and acting via the diploOffer / breakTreaty /
 * declareWar commands. One card per contacted empire shows the current
 * relationship and the actions the server will currently accept (offer
 * trade/peace/pact/alliance, break a treaty, declare war). The target's
 * diplomat AI answers offers immediately; verdicts arrive as diploReply and are
 * appended to the reply log at the bottom.
 *
 * DTO-driven, holds no game model — mirrors the eventual browser client. The
 * legality of each action lives in the pure {@link Diplomacy} helper.
 */
public class RacesPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private final Consumer<Object> orderSender;
    private final JPanel cards = new JPanel();
    private final JTextArea replyLog = new JTextArea(6, 30);

    private PlayerView lastView;

    public RacesPanel(Consumer<Object> orderSender) {
        this.orderSender = orderSender;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setLayout(new BorderLayout(0, 8));

        JLabel title = new JLabel("Races");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        add(title, BorderLayout.NORTH);

        cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS));
        add(new JScrollPane(cards), BorderLayout.CENTER);

        replyLog.setEditable(false);
        replyLog.setLineWrap(true);
        replyLog.setWrapStyleWord(true);
        JScrollPane logScroll = new JScrollPane(replyLog);
        logScroll.setBorder(BorderFactory.createTitledBorder("Diplomatic replies"));
        add(logScroll, BorderLayout.SOUTH);

        showNoContacts();
    }

    /** rebuild the empire cards from a fresh view (diplomacy actions are one-shot,
     * so there is no local dirty state to preserve) */
    public void updateFromView(PlayerView view) {
        if (view == null)
            return;
        lastView = view;
        cards.removeAll();
        List<EmpireDto> others = Diplomacy.contacted(view);
        if (others.isEmpty())
            showNoContacts();
        else
            for (EmpireDto e : others)
                cards.add(buildCard(e));
        cards.revalidate();
        cards.repaint();
    }

    /** show the AI's answer to a diplomatic offer in the reply log */
    public void showReply(Messages.DiploReply dr) {
        if (dr == null)
            return;
        String name = empireName(dr.empireId);
        String verdict = dr.accepted ? "accepted" : "refused";
        String extra = ((dr.text == null) || dr.text.isEmpty()) ? "" : " - " + dr.text;
        replyLog.append(name + " " + verdict + " your " + dr.action + " offer" + extra + "\n");
        replyLog.setCaretPosition(replyLog.getDocument().getLength());
    }

    private void showNoContacts() {
        JLabel none = new JLabel("No other races contacted yet - send scouts to explore.");
        none.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        cards.add(none);
    }

    private JPanel buildCard(EmpireDto e) {
        JPanel card = new JPanel(new BorderLayout(6, 4));
        card.setBorder(BorderFactory.createTitledBorder(Diplomacy.displayName(e)));
        card.add(new JLabel(Diplomacy.statusLabel(e)), BorderLayout.NORTH);

        JPanel actions = new JPanel(new GridLayout(0, 2, 6, 4));

        // offer trade, with a level spinner (1..maxTradeLevel)
        boolean canTrade = Diplomacy.canOfferTrade(e);
        int maxLevel = Math.max(1, e.maxTradeLevel);
        JSpinner tradeLevel = new JSpinner(new SpinnerNumberModel(maxLevel, 1, maxLevel, 1));
        tradeLevel.setEnabled(canTrade);
        JButton trade = new JButton("Offer Trade");
        trade.setEnabled(canTrade);
        trade.addActionListener(a -> offer(e.id, "TRADE", (Integer) tradeLevel.getValue()));
        JPanel tradeRow = new JPanel(new BorderLayout(4, 0));
        tradeRow.add(trade, BorderLayout.CENTER);
        tradeRow.add(tradeLevel, BorderLayout.EAST);
        actions.add(tradeRow);

        actions.add(actionButton("Offer Peace", Diplomacy.canOfferPeace(e), () -> offer(e.id, "PEACE", 0)));
        actions.add(actionButton("Offer Pact", Diplomacy.canOfferPact(e), () -> offer(e.id, "PACT", 0)));
        actions.add(actionButton("Offer Alliance", Diplomacy.canOfferAlliance(e), () -> offer(e.id, "ALLIANCE", 0)));

        // break an existing treaty (only the ones currently in force are listed)
        List<String> breakable = Diplomacy.breakableTreaties(e);
        JComboBox<String> treaty = new JComboBox<>(breakable.toArray(new String[0]));
        JButton breakBtn = new JButton("Break Treaty");
        boolean canBreak = !breakable.isEmpty();
        treaty.setEnabled(canBreak);
        breakBtn.setEnabled(canBreak);
        breakBtn.addActionListener(a -> {
            String t = (String) treaty.getSelectedItem();
            if (t != null)
                breakTreaty(e.id, t);
        });
        JPanel breakRow = new JPanel(new BorderLayout(4, 0));
        breakRow.add(breakBtn, BorderLayout.CENTER);
        breakRow.add(treaty, BorderLayout.EAST);
        actions.add(breakRow);

        actions.add(actionButton("Declare War", Diplomacy.canDeclareWar(e), () -> declareWar(e.id)));

        card.add(actions, BorderLayout.CENTER);
        return card;
    }

    private JButton actionButton(String label, boolean enabled, Runnable action) {
        JButton b = new JButton(label);
        b.setEnabled(enabled);
        b.addActionListener(a -> action.run());
        return b;
    }

    private void offer(int empireId, String action, int tradeLevel) {
        Messages.DiploOffer o = new Messages.DiploOffer();
        o.empireId = empireId;
        o.action = action;
        o.tradeLevel = tradeLevel;
        orderSender.accept(o);
    }

    private void breakTreaty(int empireId, String treaty) {
        Messages.BreakTreaty b = new Messages.BreakTreaty();
        b.empireId = empireId;
        b.treaty = treaty;
        orderSender.accept(b);
    }

    private void declareWar(int empireId) {
        Messages.DeclareWar w = new Messages.DeclareWar();
        w.empireId = empireId;
        orderSender.accept(w);
    }

    private String empireName(int empireId) {
        if ((lastView != null) && (lastView.empires != null))
            for (EmpireDto e : lastView.empires)
                if (e.id == empireId)
                    return Diplomacy.displayName(e);
        return "Empire " + empireId;
    }
}
